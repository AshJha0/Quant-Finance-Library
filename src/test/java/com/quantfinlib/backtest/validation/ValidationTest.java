package com.quantfinlib.backtest.validation;

import com.quantfinlib.TestData;
import com.quantfinlib.backtest.BacktestConfig;
import com.quantfinlib.backtest.PerformanceMetrics;
import com.quantfinlib.backtest.strategies.SmaCrossStrategy;
import com.quantfinlib.core.BarSeries;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidationTest {

    private static final StrategyFactory SMA_FACTORY = params ->
            new SmaCrossStrategy(params.get("fast").intValue(), params.get("slow").intValue());

    private static final ParameterGrid GRID = new ParameterGrid()
            .add("fast", 5, 10)
            .add("slow", 20, 40);

    @Test
    void parameterGridEnumeratesCartesianProduct() {
        assertEquals(4, GRID.size());
        List<Map<String, Double>> combos = GRID.combinations();
        assertEquals(4, combos.size());
        assertEquals(Map.of("fast", 5.0, "slow", 20.0), combos.getFirst());
        assertEquals(Map.of("fast", 10.0, "slow", 40.0), combos.getLast());
    }

    @Test
    void gridSearchRanksByObjective() {
        BarSeries s = BarSeries.of("CYCLE", TestData.sineTrend(600, 100, 0.02, 12, 60));
        List<GridSearchOptimizer.Candidate> ranked = GridSearchOptimizer.search(
                GRID, SMA_FACTORY, s, BacktestConfig.defaults(), PerformanceMetrics::sharpeRatio);

        assertEquals(4, ranked.size());
        for (int i = 1; i < ranked.size(); i++) {
            assertTrue(ranked.get(i - 1).objective() >= ranked.get(i).objective());
        }
        assertEquals(ranked.getFirst().params(),
                GridSearchOptimizer.best(GRID, SMA_FACTORY, s,
                        BacktestConfig.defaults(), PerformanceMetrics::sharpeRatio).params());
    }

    @Test
    void walkForwardStitchesOutOfSampleEquity() {
        BarSeries s = BarSeries.of("CYCLE", TestData.sineTrend(1_000, 100, 0.02, 12, 60));
        WalkForwardAnalyzer.WalkForwardResult result = WalkForwardAnalyzer.analyze(
                s, GRID, SMA_FACTORY, BacktestConfig.defaults(),
                300, 100, PerformanceMetrics::sharpeRatio);

        // (1000 - 300) / 100 = 7 folds.
        assertEquals(7, result.folds().size());
        assertEquals(7 * 100, result.outOfSampleEquity().length);
        // Capital carries across folds: each fold starts where the last ended.
        WalkForwardAnalyzer.Fold first = result.folds().getFirst();
        assertEquals(0, first.trainFrom());
        assertEquals(300, first.testFrom());
        assertEquals(400, first.testTo());
        assertTrue(Double.isFinite(result.efficiency()));
        assertTrue(Double.isFinite(result.outOfSampleMetrics().totalReturn()));
    }

    @Test
    void probabilisticSharpeBehavesSanely() {
        // Strong Sharpe over a long track vs zero benchmark: near certainty.
        double high = SharpeValidation.probabilisticSharpe(0.15, 0, 1_000, 0, 3);
        assertTrue(high > 0.99, "psr=" + high);
        // Same Sharpe over a tiny track: much less confident.
        double low = SharpeValidation.probabilisticSharpe(0.15, 0, 20, 0, 3);
        assertTrue(low < high);
        // Negative skew and fat tails reduce confidence.
        double uglyTails = SharpeValidation.probabilisticSharpe(0.15, 0, 1_000, -1.5, 8);
        assertTrue(uglyTails < high);
    }

    @Test
    void deflatedSharpeAppliesMultipleTestingHaircut() {
        // 100 trials whose Sharpes are pure noise around zero.
        java.util.SplittableRandom rnd = new java.util.SplittableRandom(9);
        double[] trials = new double[100];
        for (int i = 0; i < trials.length; i++) {
            trials[i] = 0.05 * rnd.nextGaussian();
        }
        double observed = 0.10;   // the "winner"
        double psr = SharpeValidation.probabilisticSharpe(observed, 0, 500, 0, 3);
        double dsr = SharpeValidation.deflatedSharpe(observed, trials, 500, 0, 3);
        // Deflation must cost confidence versus the naive zero benchmark.
        assertTrue(dsr < psr, "dsr=" + dsr + " psr=" + psr);
        assertTrue(SharpeValidation.expectedMaxSharpe(100, 0.0025) > 0);
    }
}
