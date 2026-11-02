package com.quantfinlib.cli;

import com.quantfinlib.TestData;
import com.quantfinlib.backtest.BacktestConfig;
import com.quantfinlib.backtest.BacktestResult;
import com.quantfinlib.backtest.Backtester;
import com.quantfinlib.backtest.PerformanceMetrics;
import com.quantfinlib.backtest.TradingStrategy;
import com.quantfinlib.core.BarSeries;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CLI's private copies of the four 2.x sample strategies (removed from
 * the public API in 3.0, decision D3). These two cases moved here from
 * {@code BacktesterTest}, where they exercised the library types.
 */
class CliSampleStrategiesTest {

    @Test
    void macdEquityNeverNegativeAndMetricsConsistent() {
        BarSeries s = TestData.gbmSeries("GBM", 600, 100, 0.1, 0.25, 21);
        BacktestResult result = Backtester.run(new MacdStrategy(), s, BacktestConfig.defaults());
        for (double e : result.equityCurveCopy()) {
            assertTrue(e >= 0);
        }
        PerformanceMetrics m = result.metrics();
        assertEquals(result.equityCurve(s.size() - 1), m.finalEquity(), 1e-9);
        assertTrue(m.maxDrawdown() >= 0 && m.maxDrawdown() <= 1);
        assertTrue(m.winRate() >= 0 && m.winRate() <= 1);
    }

    @Test
    void sampleStrategiesRunWithoutError() {
        BarSeries s = TestData.gbmSeries("X", 400, 100, 0.08, 0.2, 33);
        for (TradingStrategy strat : new TradingStrategy[]{
                new EmaCrossStrategy(10, 30),
                new RsiStrategy(14, 30, 70),
                new MacdStrategy(),
                new BollingerBandsStrategy()}) {
            BacktestResult r = Backtester.run(strat, s, BacktestConfig.defaults());
            assertFalse(r.equityCurveLength() == 0);
        }
    }
}
