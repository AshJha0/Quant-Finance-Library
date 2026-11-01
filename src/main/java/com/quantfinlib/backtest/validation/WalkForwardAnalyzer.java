package com.quantfinlib.backtest.validation;

import com.quantfinlib.backtest.BacktestConfig;
import com.quantfinlib.backtest.BacktestResult;
import com.quantfinlib.backtest.Backtester;
import com.quantfinlib.backtest.PerformanceAnalytics;
import com.quantfinlib.backtest.PerformanceMetrics;
import com.quantfinlib.backtest.Trade;
import com.quantfinlib.core.BarSeries;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * Walk-forward analysis. The series is split into rolling train/test
 * windows; on each fold the parameter grid is optimised on the train window
 * only and the winner trades the following test window. Test segments are
 * stitched into one continuous out-of-sample equity curve.
 *
 * <p><b>Continuity across folds.</b> The book is carried from one test
 * window into the next ({@link Backtester#runSegment}): a position open at a
 * fold boundary stays open and is managed by the next fold's strategy, as it
 * would be live. Only the last fold liquidates at the end of the data. Trade
 * indices are absolute indices into {@code series}.</p>
 *
 * <p><b>Warm indicators.</b> Each test window is run on the slice
 * {@code [trainFrom, testTo)} with trading starting at the test boundary, so
 * indicators enter the test window warm and nothing after {@code testTo} is
 * visible to the strategy's {@code init}.</p>
 *
 * <p><b>Efficiency</b> is {@code objective(stitched OOS curve) /
 * objective(stitched IS curve)}, where the IS curve chains each fold
 * winner's in-sample per-bar returns. A ratio of whole-curve objectives,
 * rather than a sum of per-fold objectives (which for a Sharpe is not a
 * Sharpe, and is dominated by short noisy windows). NaN when the IS
 * objective is not positive: a ratio of two failures is not "robust".
 * The IS runs are cold (they start on the train window's first bar) while
 * OOS runs are warm; IS and OOS trade counts are reported so the asymmetry
 * is visible.</p>
 */
public final class WalkForwardAnalyzer {

    public record Fold(int trainFrom, int trainTo, int testFrom, int testTo,
                       Map<String, Double> bestParams,
                       double inSampleObjective, double outOfSampleObjective) {
    }

    /**
     * @param inSampleEquity      chained in-sample equity of the fold winners
     * @param inSampleTradeCount  trades the winners made in sample
     * @param efficiency          OOS objective over IS objective on the stitched curves
     */
    public record WalkForwardResult(List<Fold> folds, double[] outOfSampleEquity,
                                    PerformanceMetrics outOfSampleMetrics,
                                    List<Trade> outOfSampleTrades, double efficiency,
                                    double[] inSampleEquity, PerformanceMetrics inSampleMetrics,
                                    int inSampleTradeCount) {
    }

    private WalkForwardAnalyzer() {
    }

    /**
     * @param trainBars bars in each optimisation window
     * @param testBars  bars in each out-of-sample window; the window rolls
     *                  forward by this amount per fold
     */
    public static WalkForwardResult analyze(BarSeries series, ParameterGrid grid,
                                            StrategyFactory factory, BacktestConfig config,
                                            int trainBars, int testBars,
                                            ToDoubleFunction<PerformanceMetrics> objective) {
        int n = series.size();
        if (trainBars < 10 || testBars < 2 || trainBars + testBars > n) {
            throw new IllegalArgumentException(
                    "invalid windows: train=" + trainBars + " test=" + testBars + " bars=" + n);
        }
        List<Fold> folds = new ArrayList<>();
        List<Double> oosEquity = new ArrayList<>();
        List<Double> isEquity = new ArrayList<>();
        isEquity.add(config.initialCapital());
        List<Trade> oosTrades = new ArrayList<>();
        int isTrades = 0;
        Backtester.State state = Backtester.State.flat(config.initialCapital());
        List<Integer> starts = new ArrayList<>();
        for (int start = 0; start + trainBars + testBars <= n; start += testBars) {
            starts.add(start);
        }
        if (starts.isEmpty()) {
            throw new IllegalArgumentException("series too short for even one fold");
        }
        for (int f = 0; f < starts.size(); f++) {
            int start = starts.get(f);
            int trainTo = start + trainBars;
            int testTo = trainTo + testBars;
            boolean last = f == starts.size() - 1;

            BarSeries train = series.slice(start, trainTo);
            GridSearchOptimizer.Candidate best =
                    GridSearchOptimizer.best(grid, factory, train, config, objective);
            BacktestResult is = Backtester.run(factory.create(best.params()), train, config);
            isTrades += is.trades().size();
            double[] isCurve = is.equityCurve();
            for (int k = 1; k < isCurve.length; k++) {
                double prev = isEquity.getLast();
                isEquity.add(prev * (isCurve[k] / isCurve[k - 1]));
            }

            BarSeries warmPlusTest = series.slice(start, testTo);
            Backtester.Segment seg = Backtester.runSegment(factory.create(best.params()),
                    warmPlusTest, config, trainBars, state, start, last);
            state = seg.endState();
            double oosScore = objective.applyAsDouble(seg.result().metrics());
            folds.add(new Fold(start, trainTo, trainTo, testTo,
                    best.params(), best.objective(), oosScore));
            for (double e : seg.result().equityCurve()) {
                oosEquity.add(e);
            }
            oosTrades.addAll(seg.result().trades());
        }
        double[] equity = toArray(oosEquity);
        double[] isEq = toArray(isEquity);
        PerformanceMetrics metrics = PerformanceAnalytics.compute(
                equity, oosTrades, config.periodsPerYear());
        PerformanceMetrics isMetrics = PerformanceAnalytics.compute(
                isEq, List.of(), config.periodsPerYear());
        double isObj = objective.applyAsDouble(isMetrics);
        double oosObj = objective.applyAsDouble(metrics);
        double efficiency = isObj > 0 ? oosObj / isObj : Double.NaN;
        return new WalkForwardResult(folds, equity, metrics, oosTrades, efficiency,
                isEq, isMetrics, isTrades);
    }

    private static double[] toArray(List<Double> list) {
        double[] a = new double[list.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = list.get(i);
        }
        return a;
    }
}
