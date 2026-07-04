package com.quantfinlib.backtest.tick;

import com.quantfinlib.data.TickFileWriter;
import com.quantfinlib.orderbook.Side;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TickBacktesterTest {

    /** Writes a single-symbol tick file: prices[i] with sizes[i] at ts=i. */
    private static Path writeTicks(Path dir, double[] prices, double[] sizes) throws Exception {
        Path file = dir.resolve("ticks.qflt");
        try (TickFileWriter w = new TickFileWriter(file)) {
            w.defineSymbol(0, "EURUSD");
            for (int i = 0; i < prices.length; i++) {
                w.write(0, prices[i], sizes[i], i);
            }
        }
        return file;
    }

    private static double[] fill(int n, double value) {
        double[] a = new double[n];
        java.util.Arrays.fill(a, value);
        return a;
    }

    /** Strategy adapter with a per-tick lambda. */
    private static TickStrategy onTick(TickAction action) {
        return new TickStrategy() {
            private TickTradingContext ctx;
            private int tickIndex;

            @Override
            public String name() {
                return "TEST";
            }

            @Override
            public void init(TickTradingContext context) {
                this.ctx = context;
            }

            @Override
            public void onTick(int symbolId, double price, double size, long ts) {
                action.on(ctx, tickIndex++, symbolId, price);
            }
        };
    }

    @FunctionalInterface
    private interface TickAction {
        void on(TickTradingContext ctx, int tickIndex, int symbolId, double price);
    }

    @Test
    void marketOrdersPayHalfTheSpread(@TempDir Path dir) throws Exception {
        Path file = writeTicks(dir, fill(5, 100.0), fill(5, 1_000));
        TickBacktester.Config cfg = TickBacktester.Config.defaults()
                .withSpreadBps(10).withCommissionBps(0);

        var result = TickBacktester.run(onTick((ctx, i, sym, px) -> {
            if (i == 0) {
                ctx.submitMarket(sym, Side.BUY, 100);
            }
        }), file, cfg);

        assertEquals(1, result.fills().size());
        // Half of 10bps on 100.00 = 5bps -> 100.05.
        assertEquals(100.05, result.fills().getFirst().price(), 1e-9);
        assertEquals(5, result.ticksProcessed());
    }

    @Test
    void limitFillsWhenPriceTradesThrough(@TempDir Path dir) throws Exception {
        Path file = writeTicks(dir, new double[]{100.0, 99.8, 99.4, 99.6}, fill(4, 500));

        var result = TickBacktester.run(onTick((ctx, i, sym, px) -> {
            if (i == 0) {
                ctx.submitLimit(sym, Side.BUY, 99.5, 200);
            }
        }), file, TickBacktester.Config.defaults().withCommissionBps(0));

        assertEquals(1, result.fills().size());
        assertEquals(99.5, result.fills().getFirst().price(), 1e-9);   // our limit, not the print
        assertEquals(200, result.fills().getFirst().quantity());
    }

    @Test
    void queuePositionDelaysFillsAtTheLevel(@TempDir Path dir) throws Exception {
        // All prints at 100.00: sizes 300, 300, 500 after order placement.
        Path file = writeTicks(dir, fill(4, 100.0), new double[]{100, 300, 300, 500});
        TickBacktester.Config cfg = TickBacktester.Config.defaults()
                .withDefaultQueueAhead(500).withCommissionBps(0);

        var result = TickBacktester.run(onTick((ctx, i, sym, px) -> {
            if (i == 0) {
                ctx.submitLimit(sym, Side.BUY, 100.0, 400);
            }
        }), file, cfg);

        // Tick 2: 300 <= 500 queued ahead -> nothing. Tick 3: 600-500=100 fills.
        // Tick 4: 1100-500=600 fillable -> remaining 300 fills.
        assertEquals(2, result.fills().size());
        assertEquals(100, result.fills().get(0).quantity());
        assertEquals(300, result.fills().get(1).quantity());
        assertEquals(2, result.fills().get(0).timestampNanos());   // filled on the third print
    }

    @Test
    void ordersCannotFillAgainstTheTriggeringPrint(@TempDir Path dir) throws Exception {
        // Order placed on the only tick that trades through its price: must NOT fill.
        Path file = writeTicks(dir, new double[]{99.0, 100.0}, fill(2, 1_000));
        var result = TickBacktester.run(onTick((ctx, i, sym, px) -> {
            if (i == 0) {
                ctx.submitLimit(sym, Side.BUY, 99.5, 100);   // placed reacting to the 99.0 print
            }
        }), file, TickBacktester.Config.defaults());
        assertTrue(result.fills().isEmpty());
    }

    @Test
    void cancelPreventsFill(@TempDir Path dir) throws Exception {
        Path file = writeTicks(dir, new double[]{100.0, 100.0, 99.0}, fill(3, 1_000));
        var result = TickBacktester.run(new TickStrategy() {
            private TickTradingContext ctx;
            private long orderId;
            private int i;

            @Override
            public String name() {
                return "CANCELER";
            }

            @Override
            public void init(TickTradingContext context) {
                this.ctx = context;
            }

            @Override
            public void onTick(int sym, double px, double size, long ts) {
                if (i == 0) {
                    orderId = ctx.submitLimit(sym, Side.BUY, 99.5, 100);
                }
                if (i == 1) {
                    assertTrue(ctx.cancel(orderId));
                }
                i++;
            }
        }, file, TickBacktester.Config.defaults());
        assertTrue(result.fills().isEmpty());   // the 99.0 print arrives after the cancel
    }

    @Test
    void roundTripPnlAndEquityAccounting(@TempDir Path dir) throws Exception {
        Path file = writeTicks(dir, new double[]{100, 100, 110, 110, 110}, fill(5, 1_000));
        TickBacktester.Config cfg = TickBacktester.Config.defaults()
                .withSpreadBps(0).withCommissionBps(0).withEquitySampleEvery(2);

        var result = TickBacktester.run(onTick((ctx, i, sym, px) -> {
            if (i == 0) {
                ctx.submitMarket(sym, Side.BUY, 100);    // buy 100 @ 100
            }
            if (i == 3) {
                ctx.submitMarket(sym, Side.SELL, 100);   // sell 100 @ 110
            }
        }), file, cfg);

        assertEquals(2, result.fills().size());
        assertEquals(1_000_000 + 100 * 10, result.finalEquity(), 1e-6);
        assertTrue(result.metrics().totalReturn() > 0);
        // Samples: initial + every 2 ticks (2) + final.
        assertEquals(1_000_000, result.sampledEquity()[0], 1e-9);
        assertEquals(result.finalEquity(),
                result.sampledEquity()[result.sampledEquity().length - 1], 1e-9);
    }

    @Test
    void marketOrderBeforeFirstPrintIsRejected(@TempDir Path dir) throws Exception {
        Path file = writeTicks(dir, fill(2, 100.0), fill(2, 100));
        boolean[] rejected = {false};
        TickBacktester.run(new TickStrategy() {
            @Override
            public String name() {
                return "EAGER";
            }

            @Override
            public void init(TickTradingContext context) {
                rejected[0] = context.submitMarket(0, Side.BUY, 10) < 0;   // no price yet
            }

            @Override
            public void onTick(int sym, double px, double size, long ts) {
            }
        }, file, TickBacktester.Config.defaults());
        assertTrue(rejected[0]);
    }
}
