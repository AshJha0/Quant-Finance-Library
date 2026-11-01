package com.quantfinlib.backtest;

import com.quantfinlib.backtest.validation.ParameterGrid;
import com.quantfinlib.backtest.validation.WalkForwardAnalyzer;
import com.quantfinlib.core.BarSeries;
import com.quantfinlib.dsl.Rule;
import com.quantfinlib.dsl.StrategyBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fill timing, stop slippage, position-aware strategies and walk-forward continuity. */
class FillTimingTest {

    private static final long DAY = 86_400_000L;

    /** Bars with distinct open and close so the fill point is visible. */
    private static BarSeries bars(double[][] ohlc) {
        BarSeries.Builder b = BarSeries.builder("X");
        for (int i = 0; i < ohlc.length; i++) {
            double[] r = ohlc[i];
            b.add(i * DAY, r[0], r[1], r[2], r[3], 1_000);
        }
        return b.build();
    }

    /** Buys on bar {@code buy}, sells on bar {@code sell}. */
    private record Script(int buy, int sell) implements TradingStrategy {
        @Override
        public String name() {
            return "script";
        }

        @Override
        public void init(BarSeries series) {
        }

        @Override
        public Signal onBar(int index) {
            return index == buy ? Signal.BUY : index == sell ? Signal.SELL : Signal.HOLD;
        }
    }

    private static final double[][] PATH = {
            {100, 101, 99, 100},   // 0
            {102, 106, 101, 105},  // 1  signal BUY at close
            {107, 109, 106, 108},  // 2
            {110, 113, 109, 112},  // 3  signal SELL at close
            {111, 112, 108, 109},  // 4
            {109, 110, 108, 109},  // 5
    };

    @Test
    void eachFillPolicyFillsAtItsPrice() {
        BarSeries s = bars(PATH);
        BacktestConfig base = BacktestConfig.frictionless().withInitialCapital(1_000);
        Trade close = Backtester.run(new Script(1, 3), s, base.withFillPolicy(FillPolicy.CLOSE))
                .trades().getFirst();
        Trade open = Backtester.run(new Script(1, 3), s, base).trades().getFirst();
        Trade nextClose = Backtester.run(new Script(1, 3), s,
                base.withFillPolicy(FillPolicy.NEXT_CLOSE)).trades().getFirst();
        assertEquals(105, close.entryPrice(), 0);
        assertEquals(112, close.exitPrice(), 0);
        assertEquals(1, close.entryIndex());
        assertEquals(107, open.entryPrice(), 0);   // open of bar 2
        assertEquals(111, open.exitPrice(), 0);    // open of bar 4
        assertEquals(2, open.entryIndex());
        assertEquals(4, open.exitIndex());
        assertEquals(108, nextClose.entryPrice(), 0);
        assertEquals(109, nextClose.exitPrice(), 0);
        assertEquals(1_000 * 111.0 / 107, Backtester.run(new Script(1, 3), s, base)
                .metrics().finalEquity(), 1e-9);
    }

    @Test
    void stopFillsPayTheSlippageAndBeatTheTarget() {
        // Entry at the open of bar 2 (107). Bar 3 trades through both a 5%
        // stop (101.65) and a 5% target (112.35): the stop wins and fills at
        // the stop less slippage.
        double[][] path = {
                {100, 100, 100, 100},
                {100, 100, 100, 100},  // BUY signal
                {107, 107, 107, 107},
                {107, 113, 100, 108},
                {108, 108, 108, 108},
        };
        BacktestConfig cfg = BacktestConfig.frictionless().withInitialCapital(1_000)
                .withSlippage(0.001).withStopLoss(0.05).withTakeProfit(0.05);
        Trade t = Backtester.run(new Script(1, -1), bars(path), cfg).trades().getFirst();
        assertEquals(Trade.REASON_STOP_LOSS, t.exitReason());
        double entry = 107 * 1.001;
        assertEquals(entry * 0.95 * 0.999, t.exitPrice(), 1e-9);
    }

    @Test
    void nextOpenEntryIsProtectedByAStopOnItsOwnBar() {
        double[][] path = {
                {100, 100, 100, 100},
                {100, 100, 100, 100},  // BUY signal
                {100, 101, 90, 95},    // filled at 100, crashes the same bar
                {95, 95, 95, 95},
        };
        BacktestConfig cfg = BacktestConfig.frictionless().withStopLoss(0.05);
        Trade t = Backtester.run(new Script(1, -1), bars(path), cfg).trades().getFirst();
        assertEquals(Trade.REASON_STOP_LOSS, t.exitReason());
        assertEquals(2, t.exitIndex());
        assertEquals(95, t.exitPrice(), 1e-12);
    }

    @Test
    void dslExitIsNotMaskedByALevelEntryRule() {
        // Entry: "price above 50" holds on every bar. Exit fires on bar 5.
        double[] closes = new double[10];
        java.util.Arrays.fill(closes, 100);
        BarSeries s = BarSeries.of("X", closes);
        Rule always = i -> true;
        Rule exitAt5 = i -> i == 5;
        BacktestResult r = StrategyBuilder.named("level").enterWhen(always).exitWhen(exitAt5)
                .build().backtest(s, BacktestConfig.frictionless());
        // Old behaviour: BUY masks the SELL, the position is only closed at end of data.
        assertEquals(Trade.REASON_SIGNAL, r.trades().getFirst().exitReason());
        assertEquals(6, r.trades().getFirst().exitIndex()); // signal at 5, next open
        // Flat again after the exit: the always-true entry re-enters.
        assertEquals(2, r.trades().size());
    }

    @Test
    void walkForwardCarriesThePositionAcrossFoldsAndReportsAbsoluteIndices() {
        // A strategy that buys once on the first bar it sees and never sells.
        double[] closes = new double[200];
        for (int i = 0; i < closes.length; i++) {
            closes[i] = 100 + i;
        }
        BarSeries s = BarSeries.of("UP", closes);
        ParameterGrid grid = new ParameterGrid().add("p", 1);
        WalkForwardAnalyzer.WalkForwardResult r = WalkForwardAnalyzer.analyze(s, grid,
                params -> new TradingStrategy() {
                    @Override
                    public String name() {
                        return "hold";
                    }

                    @Override
                    public void init(BarSeries series) {
                    }

                    @Override
                    public Signal onBar(int index) {
                        return Signal.BUY;
                    }
                }, BacktestConfig.defaults(), 50, 30,
                com.quantfinlib.backtest.PerformanceMetrics::totalReturn);
        assertTrue(r.folds().size() >= 4);
        // One position held across every fold: exactly one trade, closed at end of data.
        assertEquals(1, r.outOfSampleTrades().size());
        Trade t = r.outOfSampleTrades().getFirst();
        assertEquals(Trade.REASON_END_OF_DATA, t.exitReason());
        assertEquals(51, t.entryIndex());   // signal at 50 (first test bar), next open
        int lastTestBar = r.folds().getLast().testTo() - 1;
        assertEquals(lastTestBar, t.exitIndex());
        assertTrue(r.inSampleTradeCount() >= r.folds().size());
        assertTrue(Double.isFinite(r.efficiency()));
    }

    @Test
    void defaultsChargeInstitutionalCosts() {
        BacktestConfig d = BacktestConfig.defaults();
        assertEquals(0.0001, d.commissionRate(), 0);
        assertEquals(0.0003, d.slippageRate(), 0);
        assertEquals(FillPolicy.NEXT_OPEN, d.fillPolicy());
    }
}
