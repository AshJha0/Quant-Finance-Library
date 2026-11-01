package com.quantfinlib.backtest.validation;

import com.quantfinlib.backtest.BacktestConfig;
import com.quantfinlib.backtest.BacktestResult;
import com.quantfinlib.backtest.Backtester;
import com.quantfinlib.backtest.PerformanceMetrics;
import com.quantfinlib.core.BarSeries;
import com.quantfinlib.util.MathUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * Exhaustive strategy parameter search: backtests every grid combination and
 * ranks by an objective (e.g. {@code PerformanceMetrics::sharpeRatio}).
 * Non-finite objectives rank last. Feed the resulting in-sample winners into
 * {@link WalkForwardAnalyzer} — never trust them raw.
 */
public final class GridSearchOptimizer {

    /** One grid point: its parameters, metrics, objective and equity curve. */
    public record Candidate(Map<String, Double> params, PerformanceMetrics metrics, double objective,
                            double[] equityCurve) {
    }

    private GridSearchOptimizer() {
    }

    public static List<Candidate> search(ParameterGrid grid, StrategyFactory factory,
                                         BarSeries series, BacktestConfig config,
                                         ToDoubleFunction<PerformanceMetrics> objective) {
        if (grid.size() == 0) {
            throw new IllegalArgumentException("empty parameter grid: nothing to search");
        }
        List<Candidate> out = new ArrayList<>(grid.size());
        for (Map<String, Double> params : grid.combinations()) {
            BacktestResult result = Backtester.run(factory.create(params), series, config);
            double score = objective.applyAsDouble(result.metrics());
            out.add(new Candidate(params, result.metrics(),
                    Double.isFinite(score) ? score : Double.NEGATIVE_INFINITY,
                    result.equityCurve()));
        }
        out.sort(Comparator.comparingDouble(Candidate::objective).reversed());
        return out;
    }

    /** The winning parameter set only. */
    public static Candidate best(ParameterGrid grid, StrategyFactory factory,
                                 BarSeries series, BacktestConfig config,
                                 ToDoubleFunction<PerformanceMetrics> objective) {
        return search(grid, factory, series, config, objective).getFirst();
    }

    /**
     * Effective number of independent trials in a search,
     * {@code N̂ = ρ̄ + (1 − ρ̄)·M} for M trials whose per-bar return streams
     * have mean pairwise correlation {@code ρ̄} (clamped to [0, 1]); the
     * average-correlation approximation in Bailey and López de Prado
     * (2014, J. Portfolio Management 40(5), §3, following the effective
     * number of tests of Bailey, Borwein, López de Prado and Zhu). Adjacent
     * grid points (fast 10/slow 50 vs fast 10/slow 55) trade almost the same
     * book; counting them as independent overstates the expected maximum.
     */
    public static double effectiveTrials(List<Candidate> ranked) {
        int m = ranked.size();
        if (m < 2) {
            return m;
        }
        double[][] r = new double[m][];
        for (int i = 0; i < m; i++) {
            double[] eq = ranked.get(i).equityCurve();
            r[i] = new double[eq.length - 1];
            for (int t = 1; t < eq.length; t++) {
                r[i][t - 1] = eq[t - 1] == 0 ? 0 : eq[t] / eq[t - 1] - 1;
            }
        }
        double sum = 0;
        int pairs = 0;
        for (int i = 0; i < m; i++) {
            for (int j = i + 1; j < m; j++) {
                double c = r[i].length == r[j].length ? MathUtils.correlation(r[i], r[j]) : 0;
                // A flat (no-trade) stream has no correlation: count it as independent.
                sum += Double.isFinite(c) ? c : 0;
                pairs++;
            }
        }
        double rho = Math.max(0, Math.min(1, sum / pairs));
        return rho + (1 - rho) * m;
    }

    /**
     * The multiple-testing haircut for the grid's winner: the probability
     * that the top-ranked candidate's Sharpe beats what the best of
     * {@link #effectiveTrials} zero-skill trials would have scored anyway
     * (the deflated Sharpe of {@link SharpeValidation#probabilisticSharpe}
     * against {@code E[max]} with N̂ in place of the raw grid size). A grid search computes a
     * Sharpe for every trial and then quietly reports only the maximum —
     * this is the one number that makes that selection honest. Values near
     * 1 mean the winner survives its own search; below ~0.95 the "best"
     * parameter set is indistinguishable from picking the luckiest of N
     * random ones.
     *
     * @param ranked         result of {@link #search} (uses every trial's
     *                       Sharpe as the null distribution), &ge; 2 trials
     * @param winnerReturns  the winner's per-period returns (derive from its
     *                       equity curve), &ge; 4 observations
     * @param periodsPerYear the annualization used by the backtest metrics
     */
    public static double deflatedSharpeOfWinner(List<Candidate> ranked, double[] winnerReturns,
                                                int periodsPerYear) {
        if (ranked.size() < 2) {
            throw new IllegalArgumentException("need >= 2 trials, got " + ranked.size());
        }
        if (winnerReturns.length < 4) {
            throw new IllegalArgumentException(
                    "need >= 4 winner returns, got " + winnerReturns.length);
        }
        if (periodsPerYear <= 0) {
            throw new IllegalArgumentException("periodsPerYear must be > 0, got " + periodsPerYear);
        }
        // SharpeValidation works in per-period units; metrics store annualized.
        double perPeriodScale = Math.sqrt(periodsPerYear);
        double[] trialSharpes = new double[ranked.size()];
        for (int i = 0; i < trialSharpes.length; i++) {
            double s = ranked.get(i).metrics().sharpeRatio() / perPeriodScale;
            trialSharpes[i] = Double.isFinite(s) ? s : 0;
        }
        double sd = MathUtils.stdDev(winnerReturns);
        double observed = sd > 0 ? MathUtils.mean(winnerReturns) / sd : 0;
        double nEff = effectiveTrials(ranked);
        double benchmark = 0;
        if (nEff > 1) {
            double sdTrials = Math.sqrt(Math.max(0, MathUtils.variance(trialSharpes)));
            double gamma = 0.5772156649015329;
            benchmark = sdTrials * ((1 - gamma) * MathUtils.normInv(1 - 1 / nEff)
                    + gamma * MathUtils.normInv(1 - 1 / (nEff * Math.E)));
        }
        return SharpeValidation.probabilisticSharpe(observed, benchmark, winnerReturns.length,
                MathUtils.skewness(winnerReturns), MathUtils.kurtosis(winnerReturns));
    }
}
