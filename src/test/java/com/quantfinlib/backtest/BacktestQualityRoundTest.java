package com.quantfinlib.backtest;

import com.quantfinlib.TestData;
import com.quantfinlib.backtest.strategies.SmaCrossStrategy;
import com.quantfinlib.backtest.tick.TickBacktester;
import com.quantfinlib.backtest.validation.GridSearchOptimizer;
import com.quantfinlib.backtest.validation.ParameterGrid;
import com.quantfinlib.backtest.validation.StrategyFactory;
import com.quantfinlib.backtest.validation.WalkForwardAnalyzer;
import com.quantfinlib.core.BarSeries;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The backtest-quality review round: known-value pins for the performance
 * formulas, the walk-forward warm-start and efficiency semantics, the
 * last-look signal-bar discipline through the engine, model-declared cost
 * sizing, and the input gates found unexercised by the audit.
 */
class BacktestQualityRoundTest {

    private static Trade trade(double pnl) {
        return new Trade("X", 0, 1, 0L, 1L, 100, 100 + pnl, 1, pnl,
                pnl / 100, Trade.REASON_SIGNAL);
    }

    private static BarSeries flatSeries(int n, double px) {
        BarSeries.Builder b = BarSeries.builder("FLAT");
        for (int i = 0; i < n; i++) {
            b.add(i, px, px, px, px, 1000);
        }
        return b.build();
    }

    /** Buys once at a fixed bar, optionally sells at another. */
    private record Script(int buyBar, int sellBar) implements TradingStrategy {
        @Override
        public String name() {
            return "SCRIPT";
        }

        @Override
        public void init(BarSeries series) {
        }

        @Override
        public Signal onBar(int index) {
            if (index == buyBar) {
                return Signal.BUY;
            }
            return index == sellBar ? Signal.SELL : Signal.HOLD;
        }
    }

    // ------------------------------------------------- PerformanceAnalytics pins

    @Test
    void profitFactorAndWinRatePinnedOnHandTrades() {
        // Gross profit 150, gross loss 100 -> PF 1.5; 2 wins of 4 -> 0.5.
        List<Trade> trades = List.of(trade(100), trade(-50), trade(50), trade(-50));
        PerformanceMetrics m = PerformanceAnalytics.compute(
                new double[]{100, 110, 105, 120}, trades, 252);
        assertEquals(1.5, m.profitFactor(), 1e-12);
        assertEquals(0.5, m.winRate(), 1e-12);
        assertEquals(4, m.tradeCount());
    }

    @Test
    void zeroPnlTradeIsNeitherWinNorLoss() {
        // The break-even trade adds nothing to gross loss, so PF is infinite;
        // but it is NOT counted as a win. One character (> vs >=) apart.
        PerformanceMetrics m = PerformanceAnalytics.compute(
                new double[]{100, 110}, List.of(trade(0), trade(100)), 252);
        assertEquals(Double.POSITIVE_INFINITY, m.profitFactor());
        assertEquals(0.5, m.winRate(), 1e-12);
    }

    @Test
    void flatCurveScoresZeroEverywhereNotNan() {
        PerformanceMetrics m = PerformanceAnalytics.compute(
                new double[]{100, 100, 100}, List.of(), 252);
        assertEquals(0.0, m.totalReturn(), 0.0);
        assertEquals(0.0, m.cagr(), 0.0);
        assertEquals(0.0, m.calmarRatio(), 0.0);
        assertEquals(0.0, m.profitFactor(), 0.0); // no profit either
        assertEquals(0.0, m.winRate(), 0.0);
    }

    @Test
    void cagrAndAnnualizedReturnPinnedExactly() {
        // Equity 100 -> 110 -> 121: two +10% periods, 2 periods/year:
        // CAGR = 1.21^(2/2) - 1 = 21%; annualized mean return = 20%.
        PerformanceMetrics m = PerformanceAnalytics.compute(
                new double[]{100, 110, 121}, List.of(), 2);
        assertEquals(0.21, m.totalReturn(), 1e-12);
        assertEquals(0.21, m.cagr(), 1e-12);
        assertEquals(0.20, m.annualizedReturn(), 1e-12);
    }

    // -------------------------------------------------- grid search + parameters

    @Test
    void emptyGridIsRefusedAndCombinationsConsistentWithSize() {
        ParameterGrid empty = new ParameterGrid();
        assertEquals(0, empty.size());
        assertTrue(empty.combinations().isEmpty(), "size()==0 must mean no combinations");
        assertThrows(IllegalArgumentException.class,
                () -> GridSearchOptimizer.search(empty, p -> new Script(2, 5),
                        flatSeries(10, 100), BacktestConfig.defaults(),
                        PerformanceMetrics::sharpeRatio));
    }

    @Test
    void nonFiniteObjectiveRanksLastNotFirst() {
        // Rising market. p=1 never trades (objective NaN -> ranked last);
        // p=2 trades and scores a finite objective -> must win.
        BarSeries.Builder b = BarSeries.builder("UP");
        for (int i = 0; i < 30; i++) {
            b.add(i, 100 + i, 100.5 + i, 99.5 + i, 100 + i, 1000);
        }
        BarSeries s = b.build();
        ParameterGrid grid = new ParameterGrid().add("p", 1, 2);
        StrategyFactory factory = params ->
                params.get("p") == 1 ? new Script(-1, -1) : new Script(2, 20);
        GridSearchOptimizer.Candidate best = GridSearchOptimizer.best(grid, factory, s,
                BacktestConfig.defaults(),
                m -> m.totalReturn() == 0 ? Double.NaN : m.totalReturn());
        assertEquals(2.0, best.params().get("p"), 0.0);
        assertTrue(Double.isFinite(best.objective()));
    }

    @Test
    void deflatedSharpeOfWinnerIsAProbabilityAndGated() {
        BarSeries s = BarSeries.of("CYCLE", TestData.sineTrend(300, 100, 0.02, 10, 60));
        ParameterGrid grid = new ParameterGrid().add("fast", 3, 5).add("slow", 15, 20);
        StrategyFactory factory = p -> new SmaCrossStrategy(
                (int) (double) p.get("fast"), (int) (double) p.get("slow"));
        List<GridSearchOptimizer.Candidate> ranked = GridSearchOptimizer.search(
                grid, factory, s, BacktestConfig.defaults(), PerformanceMetrics::sharpeRatio);
        assertEquals(4, ranked.size());

        BacktestResult winner = Backtester.run(factory.create(ranked.getFirst().params()),
                s, BacktestConfig.defaults());
        double[] eq = winner.equityCurve();
        double[] rets = new double[eq.length - 1];
        for (int i = 1; i < eq.length; i++) {
            rets[i - 1] = eq[i] / eq[i - 1] - 1;
        }
        double dsr = GridSearchOptimizer.deflatedSharpeOfWinner(ranked, rets, 252);
        assertTrue(dsr >= 0 && dsr <= 1, "DSR is a probability, got " + dsr);

        assertThrows(IllegalArgumentException.class,
                () -> GridSearchOptimizer.deflatedSharpeOfWinner(ranked.subList(0, 1), rets, 252));
        assertThrows(IllegalArgumentException.class,
                () -> GridSearchOptimizer.deflatedSharpeOfWinner(ranked, new double[]{0.01}, 252));
        assertThrows(IllegalArgumentException.class,
                () -> GridSearchOptimizer.deflatedSharpeOfWinner(ranked, rets, 0));
    }

    // ------------------------------------------------------- walk-forward honesty

    @Test
    void walkForwardFoldsTradeBecauseIndicatorsArriveWarm() {
        // SmaCross(5,20) with 50-bar test folds: evaluated cold, the first
        // 20 bars of every fold are forced HOLD; warm, the strategy can act
        // from the first test bar and a cyclical market produces trades.
        BarSeries s = BarSeries.of("CYCLE", TestData.sineTrend(400, 100, 0.02, 10, 60));
        ParameterGrid grid = new ParameterGrid().add("fast", 5).add("slow", 20);
        StrategyFactory factory = p -> new SmaCrossStrategy(
                (int) (double) p.get("fast"), (int) (double) p.get("slow"));
        WalkForwardAnalyzer.WalkForwardResult r = WalkForwardAnalyzer.analyze(
                s, grid, factory, BacktestConfig.defaults(), 100, 50,
                PerformanceMetrics::totalReturn);
        assertEquals(6, r.folds().size());
        assertEquals(6 * 50, r.outOfSampleEquity().length);
        assertTrue(!r.outOfSampleTrades().isEmpty(),
                "warm folds must actually trade on a cyclical market");
    }

    @Test
    void efficiencyIsNanWhenInSampleObjectiveIsNegative() {
        // A ratio of two losses is not "robustness": isSum <= 0 -> NaN.
        BarSeries s = BarSeries.of("CYCLE", TestData.sineTrend(400, 100, 0.02, 10, 60));
        ParameterGrid grid = new ParameterGrid().add("fast", 5).add("slow", 20);
        StrategyFactory factory = p -> new SmaCrossStrategy(5, 20);
        WalkForwardAnalyzer.WalkForwardResult r = WalkForwardAnalyzer.analyze(
                s, grid, factory, BacktestConfig.defaults(), 100, 50, m -> -1.0);
        assertTrue(Double.isNaN(r.efficiency()));
    }

    @Test
    void warmupOverloadTradesOnlyFromTradeFromAndGates() {
        BarSeries s = flatSeries(12, 100);
        // Signal fires at bar 2, but trading starts at bar 5: ignored.
        BacktestResult r = Backtester.run(new Script(2, -1), s,
                BacktestConfig.defaults(), 5);
        assertEquals(12 - 5, r.equityCurve().length);
        assertTrue(r.trades().isEmpty(), "signal before tradeFrom must be ignored");

        // Signal at bar 6 IS acted on; force-closed at end of data.
        BacktestResult r2 = Backtester.run(new Script(6, -1), s,
                BacktestConfig.defaults(), 5);
        assertEquals(1, r2.trades().size());
        assertEquals(6, r2.trades().getFirst().entryIndex());

        assertThrows(IllegalArgumentException.class,
                () -> Backtester.run(new Script(2, -1), s, BacktestConfig.defaults(), -1));
        assertThrows(IllegalArgumentException.class,
                () -> Backtester.run(new Script(2, -1), s, BacktestConfig.defaults(), 12));
    }

    // ------------------------------------------- last-look through the engine

    @Test
    void lastLookHoldsOnTheSignalBarAndFillsNextOpen() {
        // Flat market, zero spread: the signal fires at bar 3's close, so
        // the FIRST possible fill is bar 4 — filling bar 3's open would be
        // intrabar time travel. Exit signal at bar 7 fills at bar 8.
        BarSeries s = flatSeries(12, 100);
        LastLookExecution model = new LastLookExecution(0, 50);
        ExecutionAwareResult r = ExecutionAwareBacktester.run(
                new Script(3, 7), s, BacktestConfig.defaults(), model);
        assertEquals(1, r.backtest().trades().size());
        Trade t = r.backtest().trades().getFirst();
        assertEquals(4, t.entryIndex(), "fill must land the bar AFTER the signal");
        assertEquals(8, t.exitIndex());
        assertEquals(100.0, t.entryPrice(), 1e-9);
        assertEquals(0, model.rejectCount(), "signal-bar holds are latency, not rejects");
    }

    @Test
    void lastLookRejectsRunawayMarketAndNeverFills() {
        // Every bar rallies 150bps open-to-close; threshold 100bps: after
        // the signal-bar hold, every attempt is a reject — the parent
        // chases and never gets filled. That is the point of the model.
        BarSeries.Builder b = BarSeries.builder("RALLY");
        for (int i = 0; i < 10; i++) {
            double open = 100 * Math.pow(1.02, i);
            b.add(i, open, open * 1.016, open, open * 1.015, 1000);
        }
        BarSeries s = b.build();
        LastLookExecution model = new LastLookExecution(0, 100);
        ExecutionAwareResult r = ExecutionAwareBacktester.run(
                new Script(3, -1), s, BacktestConfig.defaults(), model);
        assertTrue(r.backtest().trades().isEmpty());
        assertEquals(0, model.fillCount());
        assertEquals(6, model.rejectCount(), "bars 4..9 all reject");
        double[] eq = r.backtest().equityCurve();
        assertEquals(100_000, eq[eq.length - 1], 1e-9, "no fill -> capital intact");
    }

    // ----------------------------------- model-declared cost sizing + force-close

    @Test
    void entrySizingUsesTheModelsDeclaredCostNotAFlatBuffer() {
        // 2.5% all-in cost: affordable = 100000/(100*1.025) = 975 shares.
        // The old flat 1% buffer would size 990 and overdraw cash by ~1.5k.
        BarSeries s = flatSeries(10, 100);
        InstantExecution model = new InstantExecution(0.015, 0.010);
        ExecutionAwareResult r = ExecutionAwareBacktester.run(
                new Script(2, 6), s, BacktestConfig.defaults(), model);
        Trade t = r.backtest().trades().getFirst();
        assertEquals(975, t.quantity(), 1e-9);
        assertEquals(102.5, t.entryPrice(), 1e-9);
    }

    @Test
    void forceCloseChargesTheModelsWorstCaseCost() {
        // Never sold: the end-of-data close pays 2.5%, not zero.
        BarSeries s = flatSeries(10, 100);
        InstantExecution model = new InstantExecution(0.015, 0.010);
        ExecutionAwareResult r = ExecutionAwareBacktester.run(
                new Script(2, -1), s, BacktestConfig.defaults(), model);
        Trade t = r.backtest().trades().getFirst();
        assertEquals(Trade.REASON_END_OF_DATA, t.exitReason());
        assertEquals(97.5, t.exitPrice(), 1e-9);
    }

    @Test
    void icebergWrapperDelegatesItsInnerModelsCost() {
        // The wrapper prices nothing itself: reporting the 1% default while
        // wrapping a 2.5% model re-opens the overdraw (990 shares at 102.5
        // = 101,475 spent from 100,000 cash). Delegation sizes 975.
        BarSeries s = flatSeries(10, 100);
        IcebergExecution model = new IcebergExecution(
                new InstantExecution(0.015, 0.010), 10_000);
        ExecutionAwareResult r = ExecutionAwareBacktester.run(
                new Script(2, 6), s, BacktestConfig.defaults(), model);
        Trade t = r.backtest().trades().getFirst();
        assertEquals(975, t.quantity(), 1e-9);
        assertEquals(102.5, t.entryPrice(), 1e-9);
    }

    @Test
    void lastLookSizesAgainstTheGappedOpenItActuallyFillsAt() {
        // Signal at bar 2's close (100); bar 3 opens gapped at 105 and the
        // move is favorable to the LP -> accepted, filled at 105. Sizing
        // against the close would buy 1000 shares for 105,000 of the
        // 100,000 cash; sizing against the model's own reference price
        // (the open) buys 952 and conserves cash.
        BarSeries.Builder b = BarSeries.builder("GAP");
        for (int i = 0; i < 3; i++) {
            b.add(i, 100, 100, 100, 100, 1000);
        }
        b.add(3, 105, 105, 99, 100, 1000);
        for (int i = 4; i < 10; i++) {
            b.add(i, 100, 100, 100, 100, 1000);
        }
        BarSeries s = b.build();
        LastLookExecution model = new LastLookExecution(0, 100);
        ExecutionAwareResult r = ExecutionAwareBacktester.run(
                new Script(2, 6), s, BacktestConfig.defaults(), model);
        Trade t = r.backtest().trades().getFirst();
        assertEquals(952, t.quantity(), 1e-9, "sized by the open it fills at");
        assertEquals(105.0, t.entryPrice(), 1e-9);
    }

    // --------------------------------------------------------------- tick config

    @Test
    void tickConfigRefusesZeroSamplingInterval() {
        assertThrows(IllegalArgumentException.class,
                () -> TickBacktester.Config.defaults().withEquitySampleEvery(0));
    }
}
