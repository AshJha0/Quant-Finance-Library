package com.quantfinlib.backtest.validation;

import com.quantfinlib.backtest.BacktestConfig;
import com.quantfinlib.backtest.BacktestResult;
import com.quantfinlib.backtest.Backtester;
import com.quantfinlib.backtest.PerformanceMetrics;
import com.quantfinlib.core.BarSeries;

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

    public record Candidate(Map<String, Double> params, PerformanceMetrics metrics, double objective) {
    }

    private GridSearchOptimizer() {
    }

    public static List<Candidate> search(ParameterGrid grid, StrategyFactory factory,
                                         BarSeries series, BacktestConfig config,
                                         ToDoubleFunction<PerformanceMetrics> objective) {
        List<Candidate> out = new ArrayList<>(grid.size());
        for (Map<String, Double> params : grid.combinations()) {
            BacktestResult result = Backtester.run(factory.create(params), series, config);
            double score = objective.applyAsDouble(result.metrics());
            out.add(new Candidate(params, result.metrics(),
                    Double.isFinite(score) ? score : Double.NEGATIVE_INFINITY));
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
}
