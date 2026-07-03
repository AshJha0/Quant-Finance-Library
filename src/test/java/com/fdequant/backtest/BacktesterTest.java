package com.fdequant.backtest;

import com.fdequant.TestData;
import com.fdequant.backtest.strategies.BollingerBandsStrategy;
import com.fdequant.backtest.strategies.MacdStrategy;
import com.fdequant.backtest.strategies.RsiStrategy;
import com.fdequant.backtest.strategies.SmaCrossStrategy;
import com.fdequant.core.BarSeries;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BacktesterTest {

    @Test
    void smaCrossTradesOnCyclicalMarket() {
        BarSeries s = BarSeries.of("CYCLE", TestData.sineTrend(500, 100, 0.02, 10, 60));
        BacktestResult result = Backtester.run(new SmaCrossStrategy(5, 20), s, BacktestConfig.defaults());

        assertEquals(s.size(), result.equityCurve().length);
        assertTrue(result.trades().size() >= 3, "expected several round trips");
        assertEquals(100_000, result.equityCurve()[0], 1e-6);
        // Every trade must close after it opens.
        result.trades().forEach(t -> assertTrue(t.exitIndex() > t.entryIndex()));
    }

    @Test
    void equityNeverNegativeAndMetricsConsistent() {
        BarSeries s = TestData.gbmSeries("GBM", 600, 100, 0.1, 0.25, 21);
        BacktestResult result = Backtester.run(new MacdStrategy(), s, BacktestConfig.defaults());
        for (double e : result.equityCurve()) {
            assertTrue(e >= 0);
        }
        PerformanceMetrics m = result.metrics();
        assertEquals(result.equityCurve()[s.size() - 1], m.finalEquity(), 1e-9);
        assertTrue(m.maxDrawdown() >= 0 && m.maxDrawdown() <= 1);
        assertTrue(m.winRate() >= 0 && m.winRate() <= 1);
    }

    @Test
    void stopLossTriggersOnCrash() {
        // Flat market, buy signal, then a 30% single-bar crash.
        BarSeries.Builder b = BarSeries.builder("CRASH");
        for (int i = 0; i < 10; i++) {
            b.add(i, 100, 101, 99, 100, 1000);
        }
        b.add(10, 100, 100, 65, 68, 1000);   // crash bar
        for (int i = 11; i < 15; i++) {
            b.add(i, 68, 69, 67, 68, 1000);
        }
        BarSeries s = b.build();

        TradingStrategy buyOnce = new TradingStrategy() {
            @Override
            public String name() {
                return "BUY_ONCE";
            }

            @Override
            public void init(BarSeries series) {
            }

            @Override
            public Signal onBar(int index) {
                return index == 2 ? Signal.BUY : Signal.HOLD;
            }
        };

        BacktestConfig cfg = BacktestConfig.defaults().withStopLoss(0.10);
        BacktestResult result = Backtester.run(buyOnce, s, cfg);

        assertEquals(1, result.trades().size());
        Trade t = result.trades().getFirst();
        assertEquals(Trade.REASON_STOP_LOSS, t.exitReason());
        assertEquals(10, t.exitIndex());
        assertTrue(t.pnl() < 0);
        // Fill at the stop (90), not the crash low (65): loss ≈ 10%, not 32%.
        assertTrue(t.returnPct() > -0.15, "returnPct=" + t.returnPct());
    }

    @Test
    void takeProfitTriggersOnRally() {
        BarSeries.Builder b = BarSeries.builder("RALLY");
        for (int i = 0; i < 5; i++) {
            b.add(i, 100, 101, 99, 100, 1000);
        }
        b.add(5, 100, 130, 100, 128, 1000);  // rally bar
        for (int i = 6; i < 10; i++) {
            b.add(i, 128, 129, 127, 128, 1000);
        }
        BarSeries s = b.build();

        TradingStrategy buyOnce = new TradingStrategy() {
            @Override
            public String name() {
                return "BUY_ONCE";
            }

            @Override
            public void init(BarSeries series) {
            }

            @Override
            public Signal onBar(int index) {
                return index == 1 ? Signal.BUY : Signal.HOLD;
            }
        };

        BacktestResult result = Backtester.run(buyOnce, s,
                BacktestConfig.defaults().withTakeProfit(0.20));
        assertEquals(1, result.trades().size());
        assertEquals(Trade.REASON_TAKE_PROFIT, result.trades().getFirst().exitReason());
        assertTrue(result.trades().getFirst().pnl() > 0);
    }

    @Test
    void openPositionForceClosedAtEndOfData() {
        BarSeries s = BarSeries.of("UP", TestData.sineTrend(100, 100, 0.5, 0, 10));
        TradingStrategy buyAndHold = new TradingStrategy() {
            @Override
            public String name() {
                return "BUY_HOLD";
            }

            @Override
            public void init(BarSeries series) {
            }

            @Override
            public Signal onBar(int index) {
                return index == 0 ? Signal.BUY : Signal.HOLD;
            }
        };
        BacktestResult result = Backtester.run(buyAndHold, s, BacktestConfig.defaults());
        assertEquals(1, result.trades().size());
        assertEquals(Trade.REASON_END_OF_DATA, result.trades().getFirst().exitReason());
        assertTrue(result.metrics().totalReturn() > 0.3);
    }

    @Test
    void builtInStrategiesRunWithoutError() {
        BarSeries s = TestData.gbmSeries("X", 400, 100, 0.08, 0.2, 33);
        for (TradingStrategy strat : new TradingStrategy[]{
                new SmaCrossStrategy(10, 30),
                new RsiStrategy(14, 30, 70),
                new MacdStrategy(),
                new BollingerBandsStrategy()}) {
            BacktestResult r = Backtester.run(strat, s, BacktestConfig.defaults());
            assertFalse(r.equityCurve().length == 0);
        }
    }
}
