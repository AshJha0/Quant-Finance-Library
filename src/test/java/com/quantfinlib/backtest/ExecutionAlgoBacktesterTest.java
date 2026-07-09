package com.quantfinlib.backtest;

import com.quantfinlib.core.BarSeries;
import com.quantfinlib.execution.BenchmarkExecutor.Benchmark;
import com.quantfinlib.orderbook.Side;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The execution desk's backtest: which benchmark algo, at what cost. */
class ExecutionAlgoBacktesterTest {

    private static BarSeries flat(int bars, double price, double volume) {
        BarSeries.Builder b = BarSeries.builder("FLAT");
        for (int i = 0; i < bars; i++) {
            b.add(i * 60_000L, price, price, price, price, volume);
        }
        return b.build();
    }

    private static BarSeries trending(int bars, double start, double step, double volume) {
        BarSeries.Builder b = BarSeries.builder("TREND");
        for (int i = 0; i < bars; i++) {
            double px = start + i * step;
            b.add(i * 60_000L, px, px, px, px, volume);
        }
        return b.build();
    }

    @Test
    void flatMarketAtZeroCostHasZeroShortfallForEveryBenchmark() {
        BarSeries series = flat(26, 100, 1_000_000);
        var bt = new ExecutionAlgoBacktester(new ExecutionAlgoBacktester.Config(
                0, 0.25, TradeCostModel.flat(0)));
        for (Benchmark b : new Benchmark[]{Benchmark.TWAP, Benchmark.VWAP,
                Benchmark.ARRIVAL_PRICE, Benchmark.IMPLEMENTATION_SHORTFALL,
                Benchmark.CLOSING_PRICE, Benchmark.OPENING_PRICE}) {
            var r = bt.run(series, Side.BUY, 100_000, b);
            assertTrue(r.completed(), b + " completes on ample liquidity");
            assertEquals(0, r.shortfallBps(), 1e-9, b + ": no move, no cost, no shortfall");
            assertEquals(0, r.vwapSlippageBps(), 1e-9);
        }
    }

    @Test
    void inATrendingMarketFrontLoadedBeatsBackLoaded() {
        BarSeries rising = trending(26, 100, 0.1, 1_000_000);
        var bt = new ExecutionAlgoBacktester(new ExecutionAlgoBacktester.Config(
                0, 0.25, TradeCostModel.flat(0)));
        double open = bt.run(rising, Side.BUY, 100_000, Benchmark.OPENING_PRICE)
                .shortfallBps();
        double twap = bt.run(rising, Side.BUY, 100_000, Benchmark.TWAP).shortfallBps();
        double close = bt.run(rising, Side.BUY, 100_000, Benchmark.CLOSING_PRICE)
                .shortfallBps();
        assertTrue(open < twap && twap < close,
                "buying a rising market: the earlier the fills, the smaller the "
                        + "shortfall (" + open + " < " + twap + " < " + close + ")");

        // The same ordering, mirrored, for a seller in a falling market.
        BarSeries falling = trending(26, 100, -0.1, 1_000_000);
        double sellOpen = bt.run(falling, Side.SELL, 100_000, Benchmark.OPENING_PRICE)
                .shortfallBps();
        double sellClose = bt.run(falling, Side.SELL, 100_000, Benchmark.CLOSING_PRICE)
                .shortfallBps();
        assertTrue(sellOpen < sellClose, "positive shortfall = cost on BOTH sides");
    }

    @Test
    void costsAppearInTheShortfallOneForOne() {
        BarSeries series = flat(26, 100, 1_000_000);
        var bt = new ExecutionAlgoBacktester(new ExecutionAlgoBacktester.Config(
                0, 0.25, TradeCostModel.flat(10)));
        var r = bt.run(series, Side.BUY, 100_000, Benchmark.TWAP);
        assertEquals(10, r.shortfallBps(), 1e-9, "a flat 10 bps cost IS the shortfall");
        var sell = bt.run(series, Side.SELL, 100_000, Benchmark.TWAP);
        assertEquals(10, sell.shortfallBps(), 1e-9, "signed as a cost for sells too");
    }

    @Test
    void participationCapsAndPovAreHonest() {
        // 26 bars x 1,000 volume x 25% cap = exactly 250/bar, 6,500 total —
        // the Config cap is the SINGLE cap (the executor's internal depth
        // fraction is disabled), so the bound is exact, not approximate.
        BarSeries thin = flat(26, 100, 1_000);
        var bt = new ExecutionAlgoBacktester(new ExecutionAlgoBacktester.Config(
                0, 0.25, TradeCostModel.flat(0)));
        var r = bt.run(thin, Side.BUY, 100_000, Benchmark.TWAP);
        assertEquals(6_500, r.executed(),
                "participationCap x volume every bar, nothing more, nothing less");
        assertTrue(!r.completed(), "an unfillable parent reports itself honestly");

        // POV at 10% of a 26M-volume session with a huge parent: the RATE
        // must bind — exactly 100k per 1M bar, 2.6M total.
        var pov = bt.run(flat(26, 100, 1_000_000), Side.BUY, 10_000_000,
                Benchmark.PARTICIPATION, 0.10);
        assertEquals(2_600_000, pov.executed(),
                "10% of every bar's volume — the rate, not the parent, binds");
        assertTrue(!pov.completed());
    }

    @Test
    void backtesterValidation() {
        var bt = new ExecutionAlgoBacktester();
        BarSeries series = flat(5, 100, 1_000);
        assertThrows(IllegalArgumentException.class,
                () -> bt.run(series, Side.BUY, 0, Benchmark.TWAP));
        assertThrows(IllegalArgumentException.class,
                () -> bt.run(series, Side.BUY, 100, Benchmark.PARTICIPATION));
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionAlgoBacktester.Config(-1, 0.1, TradeCostModel.flat(0)));
    }
}
