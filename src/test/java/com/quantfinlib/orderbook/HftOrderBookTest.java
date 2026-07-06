package com.quantfinlib.orderbook;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The venue-grade book: direct price-time semantics, a model-based
 * equivalence run against the reference {@link OrderBook} (identical random
 * operation streams must produce identical books and identical traded
 * volume), zero-allocation proof, id-map churn stress, and the rejection
 * codes' venue semantics.
 */
class HftOrderBookTest {

    private static final int MIN = 900;
    private static final int MAX = 1100;

    private static HftOrderBook book(int maxOrders) {
        return new HftOrderBook(MIN, MAX, maxOrders);
    }

    // ------------------------------------------------------------------
    // Direct semantics
    // ------------------------------------------------------------------

    @Test
    void priceTimePriorityAndPartialFills() {
        HftOrderBook b = book(64);
        List<long[]> trades = new ArrayList<>(); // maker, taker, tick, qty
        b.tradeSink((maker, taker, tick, qty, ts) -> trades.add(new long[]{maker, taker, tick, qty}));

        long a1 = b.submitLimit(Side.SELL, 1001, 30, 1); // first at the level
        long a2 = b.submitLimit(Side.SELL, 1001, 30, 2); // second: behind a1
        long a3 = b.submitLimit(Side.SELL, 1002, 50, 3); // worse price
        assertEquals(1001, b.bestAskTick());
        assertEquals(60, b.bestAskSize());

        // Crossing buy for 70: exhausts 1001 FIFO (a1 then a2), then 10 off a3.
        long taker = b.submitLimit(Side.BUY, 1002, 70, 4);
        assertEquals(3, trades.size());
        assertEquals(a1, trades.get(0)[0]);       // time priority within level
        assertEquals(30, trades.get(0)[3]);
        assertEquals(a2, trades.get(1)[0]);
        assertEquals(a3, trades.get(2)[0]);       // price priority across levels
        assertEquals(1002, trades.get(2)[2]);
        assertEquals(10, trades.get(2)[3]);
        assertEquals(taker, trades.get(0)[1]);

        // a3 keeps its remainder; the taker rested nothing (fully filled).
        assertEquals(40, b.openQuantity(a3));
        assertEquals(0, b.openQuantity(taker));
        assertEquals(1002, b.bestAskTick());
        assertEquals(Integer.MIN_VALUE, b.bestBidTick()); // no bids
        assertEquals(3, b.tradeCount());
    }

    @Test
    void restingCancelAndBestTracking() {
        HftOrderBook b = book(64);
        long b1 = b.submitLimit(Side.BUY, 1000, 10, 1);
        long b2 = b.submitLimit(Side.BUY, 995, 20, 2);
        // Levels > 64 ticks apart so best-advancement crosses bitmap words.
        long b3 = b.submitLimit(Side.BUY, 920, 30, 3);
        assertEquals(1000, b.bestBidTick());

        assertTrue(b.cancel(b1));
        assertEquals(995, b.bestBidTick());     // best walks down
        assertTrue(b.cancel(b2));
        assertEquals(920, b.bestBidTick());     // across the word boundary
        assertFalse(b.cancel(b1));               // idempotent: already gone
        assertTrue(b.cancel(b3));
        assertEquals(Integer.MIN_VALUE, b.bestBidTick());
        assertEquals(0, b.restingOrders());

        // Depth snapshot into caller arrays: zero-alloc query contract.
        b.submitLimit(Side.SELL, 1001, 5, 4);
        b.submitLimit(Side.SELL, 1003, 7, 5);
        int[] ticks = new int[4];
        long[] qtys = new long[4];
        assertEquals(2, b.snapshot(Side.SELL, ticks, qtys));
        assertEquals(1001, ticks[0]);
        assertEquals(5, qtys[0]);
        assertEquals(1003, ticks[1]);
    }

    @Test
    void rejectionCodesAreVenueSemantics() {
        HftOrderBook b = book(2);
        assertEquals(HftOrderBook.REJECT_INVALID, b.submitLimit(Side.BUY, 1000, 0, 1));
        assertEquals(HftOrderBook.REJECT_OUT_OF_BAND, b.submitLimit(Side.BUY, MAX + 1, 10, 1));
        assertEquals(HftOrderBook.REJECT_OUT_OF_BAND, b.submitLimit(Side.BUY, MIN - 1, 10, 1));

        assertTrue(b.submitLimit(Side.SELL, 1001, 10, 1) > 0);
        assertTrue(b.submitLimit(Side.SELL, 1002, 10, 2) > 0);
        // Pool full: a third RESTING order is rejected. Note the invariant
        // this test originally got wrong: a capacity reject can never follow
        // executions, because a taker only has a remainder after fully
        // filling (and thereby freeing) every maker it crossed — matching
        // frees pool slots before resting ever needs one.
        long[] traded = {0};
        b.tradeSink((m, t, tick, qty, ts) -> traded[0] += qty);
        assertEquals(HftOrderBook.REJECT_POOL_FULL, b.submitLimit(Side.BUY, 1000, 5, 3));
        assertEquals(0, traded[0]);
        // A crossing order through a full pool works fine: the fill frees
        // the maker's slot, and the remainder rests in it.
        long id = b.submitLimit(Side.BUY, 1001, 25, 4);
        assertTrue(id > 0);
        assertEquals(10, traded[0]);            // lifted the 1001 maker
        assertEquals(15, b.openQuantity(id));   // remainder rested at 1001
        assertEquals(1002, b.bestAskTick());

        assertThrows(IllegalArgumentException.class, () -> new HftOrderBook(10, 5, 8));
        assertEquals(0, b.submitMarket(Side.BUY, 0, 1));
    }

    @Test
    void marketOrdersSweepAndNeverRest() {
        HftOrderBook b = book(64);
        b.submitLimit(Side.SELL, 1001, 10, 1);
        b.submitLimit(Side.SELL, 1005, 10, 2);
        assertEquals(15, b.submitMarket(Side.BUY, 15, 3)); // sweeps two levels
        assertEquals(5, b.qtyAtTick(Side.SELL, 1005));
        // Empty bid side: a sell market fills nothing and rests nothing.
        assertEquals(0, b.submitMarket(Side.SELL, 20, 4));
        assertEquals(0, b.restingOrders() - 1);            // only the 1005 remainder rests
    }

    // ------------------------------------------------------------------
    // Model-based equivalence: the readable book is the specification
    // ------------------------------------------------------------------

    @Test
    void matchesTheReferenceOrderBookUnderRandomOperations() {
        HftOrderBook fast = book(1 << 14);
        OrderBook reference = new OrderBook("EQIV");
        long[] fastTraded = {0};
        long[] refTraded = {0};
        fast.tradeSink((m, t, tick, qty, ts) -> fastTraded[0] += qty);
        reference.addTradeListener((m, t, price, qty, ts) -> refTraded[0] += qty);

        // Alive resting pairs (fastId, refId) for cancel targeting.
        List<long[]> alive = new ArrayList<>();
        SplittableRandom rnd = new SplittableRandom(20260706);

        for (int op = 0; op < 20_000; op++) {
            int kind = rnd.nextInt(10);
            if (kind < 6 || alive.isEmpty()) {
                Side side = rnd.nextBoolean() ? Side.BUY : Side.SELL;
                int tick = MIN + rnd.nextInt(MAX - MIN + 1);
                long qty = 1 + rnd.nextInt(100);
                long fastId = fast.submitLimit(side, tick, qty, op);
                long refId = reference.submitLimit(side, tick / 100.0, qty, op);
                assertTrue(fastId > 0);
                // Both books must agree on whether the order rested.
                long fastOpen = fast.openQuantity(fastId);
                LimitOrder refOrder = reference.order(refId);
                assertEquals(refOrder != null, fastOpen > 0, "resting disagreement at op " + op);
                if (fastOpen > 0) {
                    assertEquals(refOrder.quantity(), fastOpen);
                    alive.add(new long[]{fastId, refId});
                }
            } else if (kind < 9) {
                long[] pair = alive.remove(rnd.nextInt(alive.size()));
                // A pair can have been consumed by matching since we saved it.
                assertEquals(reference.cancel(pair[1]), fast.cancel(pair[0]),
                        "cancel disagreement at op " + op);
            } else {
                Side side = rnd.nextBoolean() ? Side.BUY : Side.SELL;
                long qty = 1 + rnd.nextInt(150);
                fast.submitMarket(side, qty, op);
                reference.submitMarket(side, qty, op);
            }

            if (op % 500 == 0 || op == 19_999) {
                compareBooks(fast, reference, op);
                assertEquals(refTraded[0], fastTraded[0], "traded volume at op " + op);
            }
        }
        assertTrue(fastTraded[0] > 0, "the run must actually exercise matching");
    }

    private static void compareBooks(HftOrderBook fast, OrderBook reference, int op) {
        double refBid = reference.bestBid();
        if (Double.isNaN(refBid)) {
            assertEquals(Integer.MIN_VALUE, fast.bestBidTick(), "empty-bid at op " + op);
        } else {
            assertEquals(Math.round(refBid * 100), fast.bestBidTick(), "best bid at op " + op);
            assertEquals(reference.bestBidSize(), fast.bestBidSize(), "bid size at op " + op);
        }
        double refAsk = reference.bestAsk();
        if (Double.isNaN(refAsk)) {
            assertEquals(Integer.MAX_VALUE, fast.bestAskTick(), "empty-ask at op " + op);
        } else {
            assertEquals(Math.round(refAsk * 100), fast.bestAskTick(), "best ask at op " + op);
            assertEquals(reference.bestAskSize(), fast.bestAskSize(), "ask size at op " + op);
        }
        // Top-of-book depth, level by level, both sides.
        for (Side side : new Side[]{Side.BUY, Side.SELL}) {
            for (double[] level : reference.levels(side, 10)) {
                assertEquals((long) level[1],
                        fast.qtyAtTick(side, (int) Math.round(level[0] * 100)),
                        "level qty at op " + op + " tick " + Math.round(level[0] * 100));
            }
        }
    }

    // ------------------------------------------------------------------
    // Id-map churn: backward-shift deletion under heavy add/cancel
    // ------------------------------------------------------------------

    @Test
    void idMapSurvivesHeavyChurn() {
        // Non-crossing orders only (bids far below asks) so every order
        // rests: pure map/pool churn, checked against a java.util reference.
        HftOrderBook b = new HftOrderBook(0, 200, 1 << 12);
        Map<Long, Long> expected = new HashMap<>();
        List<Long> aliveIds = new ArrayList<>();
        SplittableRandom rnd = new SplittableRandom(7);
        for (int op = 0; op < 200_000; op++) {
            if (rnd.nextBoolean() || aliveIds.isEmpty()) {
                if (expected.size() >= (1 << 12) - 1) {
                    continue; // stay under pool capacity
                }
                boolean buy = rnd.nextBoolean();
                int tick = buy ? rnd.nextInt(90) : 110 + rnd.nextInt(90);
                long qty = 1 + rnd.nextInt(1000);
                long id = b.submitLimit(buy ? Side.BUY : Side.SELL, tick, qty, op);
                assertTrue(id > 0);
                expected.put(id, qty);
                aliveIds.add(id);
            } else {
                long id = aliveIds.remove(rnd.nextInt(aliveIds.size()));
                assertTrue(b.cancel(id));
                expected.remove(id);
            }
        }
        // Every survivor must be found with its exact quantity; every
        // cancelled id must be gone — backward-shift left no ghosts.
        for (Map.Entry<Long, Long> e : expected.entrySet()) {
            assertEquals(e.getValue(), b.openQuantity(e.getKey()), "id " + e.getKey());
        }
        assertEquals(expected.size(), b.restingOrders());
    }

    // ------------------------------------------------------------------
    // The claim that defines the class
    // ------------------------------------------------------------------

    @Test
    void steadyStateOperationsAreAllocationFree() {
        HftOrderBook b = book(1 << 12);
        b.tradeSink((m, t, tick, qty, ts) -> { /* blackhole */ });
        // Pre-sized id ring for cancel targeting: the TEST must not allocate
        // in the measured loop either.
        long[] ring = new long[1024];
        int ringSize = 0;
        SplittableRandom rnd = new SplittableRandom(42);
        // JIT warm-up over the same op mix.
        for (int i = 0; i < 200_000; i++) {
            ringSize = churn(b, rnd, ring, ringSize, i);
        }
        var mx = (com.sun.management.ThreadMXBean)
                java.lang.management.ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long before = mx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < 500_000; i++) {
            ringSize = churn(b, rnd, ring, ringSize, i);
        }
        long allocated = mx.getThreadAllocatedBytes(tid) - before;
        assertTrue(allocated < 100_000,
                "book churn allocated " + allocated + " bytes (trades=" + b.tradeCount() + ")");
        assertTrue(b.tradeCount() > 0, "the loop must exercise matching, not just resting");
    }

    /** One mixed operation: add (crossing ~sometimes) or cancel from the ring. */
    private static int churn(HftOrderBook b, SplittableRandom rnd, long[] ring, int ringSize,
                             int op) {
        if (rnd.nextInt(10) < 7 || ringSize == 0) {
            Side side = rnd.nextBoolean() ? Side.BUY : Side.SELL;
            int tick = MIN + rnd.nextInt(MAX - MIN + 1);
            long id = b.submitLimit(side, tick, 1 + rnd.nextInt(50), op);
            if (id > 0 && b.openQuantity(id) > 0 && ringSize < ring.length) {
                ring[ringSize++] = id;
            }
        } else {
            int pick = rnd.nextInt(ringSize);
            b.cancel(ring[pick]);
            ring[pick] = ring[--ringSize];  // swap-remove, no allocation
        }
        return ringSize;
    }
}
