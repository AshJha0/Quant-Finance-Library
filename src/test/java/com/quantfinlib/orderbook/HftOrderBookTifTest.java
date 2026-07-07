package com.quantfinlib.orderbook;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Time-in-force order types on the venue-grade book: IOC, FOK, post-only. */
class HftOrderBookTifTest {

    private static final int MIN = 9_000;
    private static final int MAX = 11_000;

    private static HftOrderBook book() {
        return new HftOrderBook(MIN, MAX, 1 << 10);
    }

    // ------------------------------------------------------------------
    // IOC
    // ------------------------------------------------------------------

    @Test
    void iocFillsWhatCrossesAndExpiresTheRest() {
        HftOrderBook b = book();
        b.submitLimit(Side.SELL, 10_000, 60, 1);
        b.submitLimit(Side.SELL, 10_001, 50, 2);

        // Limit 10_000: only the first level is within reach.
        assertEquals(60, b.submitIoc(Side.BUY, 10_000, 100, 3));
        // Nothing rested: the 40-share remainder expired.
        assertEquals(0, b.qtyAtTick(Side.BUY, 10_000));
        assertEquals(1, b.restingOrders());
        assertEquals(10_001, b.bestAskTick());
    }

    @Test
    void iocAgainstAnEmptySideFillsNothing() {
        HftOrderBook b = book();
        assertEquals(0, b.submitIoc(Side.BUY, 10_500, 100, 1));
        assertEquals(0, b.submitIoc(Side.SELL, 9_500, 100, 2));
        assertEquals(0, b.restingOrders());
    }

    @Test
    void iocWithAggressiveOffBandLimitActsAsMarket() {
        HftOrderBook b = book();
        b.submitLimit(Side.SELL, 10_999, 30, 1);
        // Buy limit far above the band top: clamped, still lifts everything.
        assertEquals(30, b.submitIoc(Side.BUY, MAX + 500, 30, 2));
        b.submitLimit(Side.BUY, 9_001, 25, 3);
        assertEquals(25, b.submitIoc(Side.SELL, MIN - 500, 25, 4));
    }

    // ------------------------------------------------------------------
    // FOK
    // ------------------------------------------------------------------

    @Test
    void fokFillsFullyOrNotAtAll() {
        HftOrderBook b = book();
        b.submitLimit(Side.SELL, 10_000, 60, 1);
        b.submitLimit(Side.SELL, 10_002, 50, 2);
        long trades0 = b.tradeCount();

        // 100 within limit 10_001: only 60 available -> killed, no trades.
        assertEquals(0, b.submitFok(Side.BUY, 10_001, 100, 3));
        assertEquals(trades0, b.tradeCount());
        assertEquals(60, b.bestAskSize());

        // 100 within limit 10_002: 110 available -> full fill across levels.
        assertEquals(100, b.submitFok(Side.BUY, 10_002, 100, 4));
        assertEquals(10, b.qtyAtTick(Side.SELL, 10_002));
        assertEquals(0, b.qtyAtTick(Side.SELL, 10_000));
    }

    @Test
    void fokProbeRespectsTheLimitPriceExactly() {
        HftOrderBook b = book();
        b.submitLimit(Side.BUY, 10_000, 40, 1);
        b.submitLimit(Side.BUY, 9_998, 40, 2);
        // Sell 80 no lower than 9_999: only the 40 at 10_000 qualifies.
        assertEquals(0, b.submitFok(Side.SELL, 9_999, 80, 3));
        // Lowering the limit to 9_998 brings the second level in.
        assertEquals(80, b.submitFok(Side.SELL, 9_998, 80, 4));
        assertEquals(0, b.restingOrders());
    }

    // ------------------------------------------------------------------
    // Post-only
    // ------------------------------------------------------------------

    @Test
    void postOnlyRestsWhenPassiveAndRejectsWhenItWouldCross() {
        HftOrderBook b = book();
        b.submitLimit(Side.SELL, 10_000, 50, 1);

        long id = b.submitPostOnly(Side.BUY, 9_999, 100, 2);
        assertTrue(id > 0, "passive post-only must rest");
        assertEquals(100, b.qtyAtTick(Side.BUY, 9_999));

        // At or through the ask: rejected, book untouched, no trades.
        assertEquals(HftOrderBook.REJECT_WOULD_CROSS,
                b.submitPostOnly(Side.BUY, 10_000, 100, 3));
        assertEquals(HftOrderBook.REJECT_WOULD_CROSS,
                b.submitPostOnly(Side.BUY, 10_500, 100, 4));
        assertEquals(0, b.tradeCount());
        assertEquals(50, b.bestAskSize());

        // Mirror on the sell side.
        assertEquals(HftOrderBook.REJECT_WOULD_CROSS,
                b.submitPostOnly(Side.SELL, 9_999, 10, 5));
        assertTrue(b.submitPostOnly(Side.SELL, 10_001, 10, 6) > 0);
    }

    @Test
    void sentinelLimitsMeanPureMarketAndNeverWrap() {
        // Integer.MIN/MAX_VALUE are the documented pure-market sentinels
        // (HftSor.route): the ladder-index math must not overflow them into
        // the opposite meaning.
        HftOrderBook b = book();
        b.submitLimit(Side.BUY, 10_000, 40, 1);
        b.submitLimit(Side.SELL, 10_002, 40, 2);

        // SELL at MIN_VALUE = sell at any price: must sweep the bids.
        assertEquals(40, b.submitIoc(Side.SELL, Integer.MIN_VALUE, 40, 3));
        // BUY at MAX_VALUE = buy at any price: must lift the asks.
        assertEquals(40, b.submitIoc(Side.BUY, Integer.MAX_VALUE, 40, 4));
        assertEquals(0, b.restingOrders());

        // And the passive extremes stay passive: nothing can cross them.
        b.submitLimit(Side.BUY, 10_000, 40, 5);
        b.submitLimit(Side.SELL, 10_002, 40, 6);
        assertEquals(0, b.submitIoc(Side.BUY, Integer.MIN_VALUE, 40, 7));
        assertEquals(0, b.submitIoc(Side.SELL, Integer.MAX_VALUE, 40, 8));
        // FOK goes through the same probe: same sentinel semantics.
        assertEquals(40, b.submitFok(Side.SELL, Integer.MIN_VALUE, 40, 9));
        assertEquals(0, b.submitFok(Side.SELL, Integer.MAX_VALUE, 40, 10));
    }

    @Test
    void fokIsAtomicUnderRandomizedBooks() {
        // Property: a FOK either fills in full or leaves the book untouched —
        // the probe and the matching walk must agree forever.
        java.util.SplittableRandom rnd = new java.util.SplittableRandom(99);
        for (int trial = 0; trial < 2_000; trial++) {
            HftOrderBook b = book();
            long[] resting = {0};
            b.tradeSink((m, t, tick, qty, ts) -> resting[0] += qty);
            for (int i = 0; i < 30; i++) {
                b.submitLimit(rnd.nextBoolean() ? Side.BUY : Side.SELL,
                        MIN + rnd.nextInt(MAX - MIN + 1), 1 + rnd.nextInt(80), i);
            }
            long tradedBefore = resting[0];
            long booked = totalBooked(b);
            Side side = rnd.nextBoolean() ? Side.BUY : Side.SELL;
            long qty = 1 + rnd.nextInt(400);
            int limit = MIN + rnd.nextInt(MAX - MIN + 1);
            long filled = b.submitFok(side, limit, qty, 999);
            if (filled == 0) {
                assertEquals(tradedBefore, resting[0], "killed FOK must emit no trades");
                assertEquals(booked, totalBooked(b), "killed FOK must not move the book");
            } else {
                assertEquals(qty, filled, "a FOK fill must be complete");
                assertEquals(booked - qty, totalBooked(b));
            }
        }
    }

    private static long totalBooked(HftOrderBook b) {
        int[] ticks = new int[MAX - MIN + 1];
        long[] qtys = new long[MAX - MIN + 1];
        long sum = 0;
        int n = b.snapshot(Side.BUY, ticks, qtys);
        for (int i = 0; i < n; i++) {
            sum += qtys[i];
        }
        n = b.snapshot(Side.SELL, ticks, qtys);
        for (int i = 0; i < n; i++) {
            sum += qtys[i];
        }
        return sum;
    }

    @Test
    void tifOperationsAreAllocationFree() {
        HftOrderBook b = book();
        b.tradeSink((m, t, tick, qty, ts) -> { /* blackhole */ });
        java.util.SplittableRandom rnd = new java.util.SplittableRandom(5);
        for (int i = 0; i < 200_000; i++) {           // warm-up
            tifChurn(b, rnd, i);
        }
        var mx = (com.sun.management.ThreadMXBean)
                java.lang.management.ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long before = mx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < 500_000; i++) {
            tifChurn(b, rnd, i);
        }
        long allocated = mx.getThreadAllocatedBytes(tid) - before;
        assertTrue(allocated < 100_000, "TIF churn allocated " + allocated + " bytes");
        assertTrue(b.tradeCount() > 0);
    }

    private static void tifChurn(HftOrderBook b, java.util.SplittableRandom rnd, int i) {
        Side side = rnd.nextBoolean() ? Side.BUY : Side.SELL;
        int tick = MIN + rnd.nextInt(MAX - MIN + 1);
        long qty = 1 + rnd.nextInt(60);
        switch (i & 3) {
            case 0 -> b.submitPostOnly(side, tick, qty, i);
            case 1 -> b.submitIoc(side, tick, qty, i);
            case 2 -> b.submitFok(side, tick, qty, i);
            default -> b.submitLimit(side, tick, qty, i);
        }
    }

    @Test
    void postOnlyValidatesLikeALimitOrder() {
        HftOrderBook b = book();
        assertEquals(HftOrderBook.REJECT_INVALID, b.submitPostOnly(Side.BUY, 10_000, 0, 1));
        assertEquals(HftOrderBook.REJECT_OUT_OF_BAND,
                b.submitPostOnly(Side.BUY, MIN - 1, 10, 2));
        // A resting post-only is a normal order: cancellable, fillable.
        long id = b.submitPostOnly(Side.BUY, 10_000, 25, 3);
        assertEquals(25, b.submitIoc(Side.SELL, 10_000, 25, 4));
        assertEquals(0, b.openQuantity(id));
    }
}
