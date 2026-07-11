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
 * Walk-forward analysis — the standard defense against overfit backtests.
 * The series is split into rolling train/test windows; on each fold the
 * parameter grid is optimized <i>only on the train window</i> and the winner
 * is evaluated on the unseen test window. Out-of-sample test segments are
 * stitched into one continuous equity curve (capital carries across folds),
 * giving honest out-of-sample metrics and the walk-forward efficiency ratio
 * (OOS / IS objective — near 1 is robust, near 0 is curve-fitting).
 *
 * <p>Each test window is evaluated WARM: the backtest sees the preceding
 * train bars for indicator warm-up but only trades from the test boundary
 * ({@link Backtester#run(com.quantfinlib.backtest.TradingStrategy, BarSeries,
 * BacktestConfig, int)}). Evaluating a bare test slice would re-compute
 * every indicator cold and force HOLD through each fold's first lookback
 * bars — systematically understating out-of-sample activity.</p>
 *
 * <p>The efficiency ratio is only meaningful when the in-sample objective
 * sum is positive; when it is zero or negative (the optimizer could not
 * find anything that even backtests well in-sample) efficiency is
 * {@code NaN} — a ratio of two losses saying "0.5" would read as robust
 * when both sides are failing.</p>
 */
public final class WalkForwardAnalyzer {

    public record Fold(int trainFrom, int trainTo, int testFrom, int testTo,
                       Map<String, Double> bestParams,
                       double inSampleObjective, double outOfSampleObjective) {
    }

    public record WalkForwardResult(List<Fold> folds, double[] outOfSampleEquity,
                                    PerformanceMetrics outOfSampleMetrics,
                                    List<Trade> outOfSampleTrades, double efficiency) {
    }

    private WalkForwardAnalyzer() {
    }

    /**
     * @param trainBars bars in each optimization window
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
        List<Trade> oosTrades = new ArrayList<>();
        double carryCapital = config.initialCapital();
        double isSum = 0, oosSum = 0;

        for (int start = 0; start + trainBars + testBars <= n; start += testBars) {
            int trainTo = start + trainBars;
            int testTo = trainTo + testBars;

            BarSeries train = series.slice(start, trainTo);
            GridSearchOptimizer.Candidate best =
                    GridSearchOptimizer.best(grid, factory, train, config, objective);

            // Warm-up = the train window; trading starts at the test boundary.
            BarSeries warmPlusTest = series.slice(start, testTo);
            BacktestResult oos = Backtester.run(factory.create(best.params()), warmPlusTest,
                    config.withInitialCapital(carryCapital), trainBars);
            double oosScore = objective.applyAsDouble(oos.metrics());

            folds.add(new Fold(start, trainTo, trainTo, testTo,
                    best.params(), best.objective(), oosScore));
            for (double e : oos.equityCurve()) {
                oosEquity.add(e);
            }
            oosTrades.addAll(oos.trades());
            carryCapital = oos.metrics().finalEquity();
            isSum += best.objective();
            oosSum += oosScore;
        }
        if (folds.isEmpty()) {
            throw new IllegalArgumentException("series too short for even one fold");
        }
        double[] equity = new double[oosEquity.size()];
        for (int i = 0; i < equity.length; i++) {
            equity[i] = oosEquity.get(i);
        }
        PerformanceMetrics metrics = PerformanceAnalytics.compute(
                equity, oosTrades, config.periodsPerYear());
        double efficiency = isSum > 0 ? oosSum / isSum : Double.NaN;
        return new WalkForwardResult(folds, equity, metrics, oosTrades, efficiency);
    }
}
