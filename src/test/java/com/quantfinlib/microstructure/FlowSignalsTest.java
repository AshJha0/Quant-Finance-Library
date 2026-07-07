package com.quantfinlib.microstructure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowSignalsTest {

    private static final long T0 = 1_000_000_000L;

    @Test
    void ofiFollowsTheBestLevelFormulation() {
        FlowSignals f = new FlowSignals(Long.MAX_VALUE / 2);   // effectively no decay
        f.onQuote(100, 500, 101, 400, T0);
        assertEquals(0, f.ofi(), 1e-12);                        // first quote seeds only

        // Bid size grows at the same price: +200.
        f.onQuote(100, 700, 101, 400, T0 + 1);
        assertEquals(200, f.ofi(), 1e-9);

        // Bid price improves: +new bid size (+300) => 500 total.
        f.onQuote(101, 300, 102, 400, T0 + 2);
        assertEquals(200 + 300 + /* ask up: +prev ask size */ 400, f.ofi(), 1e-9);

        // Ask drops (selling pressure): -new ask size.
        f.onQuote(101, 300, 101, 250, T0 + 3);
        assertEquals(900 - 250, f.ofi(), 1e-9);

        // Bid drops: -prev bid size.
        f.onQuote(100, 100, 101, 250, T0 + 4);
        assertEquals(650 - 300, f.ofi(), 1e-9);
    }

    @Test
    void ofiDecaysTowardZeroWithTheConfiguredHalfLife() {
        long halfLife = 1_000_000L;                            // 1 ms
        FlowSignals f = new FlowSignals(halfLife);
        f.onQuote(100, 500, 101, 400, T0);
        f.onQuote(100, 900, 101, 400, T0 + 1);                 // OFI = +400
        assertEquals(400, f.ofi(), 1e-6);
        assertEquals(200, f.ofi(T0 + 1 + halfLife), 1e-6);     // one half-life later
        assertEquals(100, f.ofi(T0 + 1 + 2 * halfLife), 1e-6);
        // A new event first decays, then adds.
        f.onQuote(100, 1300, 101, 400, T0 + 1 + halfLife);     // +400 on top of 200
        assertEquals(600, f.ofi(), 1e-6);
    }

    @Test
    void queueImbalanceReadsTheInside() {
        FlowSignals f = new FlowSignals();
        assertEquals(0, f.queueImbalance());
        f.onQuote(100, 300, 101, 100, T0);
        assertEquals(0.5, f.queueImbalance(), 1e-12);
        f.onQuote(100, 100, 101, 300, T0 + 1);
        assertEquals(-0.5, f.queueImbalance(), 1e-12);
    }

    @Test
    void tradeImbalanceIsSignedOverTotalVolume() {
        FlowSignals f = new FlowSignals(Long.MAX_VALUE / 2);
        assertEquals(0, f.tradeImbalance());
        f.onTrade(true, 300, T0);
        assertEquals(1.0, f.tradeImbalance(), 1e-12);
        f.onTrade(false, 100, T0 + 1);
        assertEquals(0.5, f.tradeImbalance(), 1e-12);          // (300-100)/400
        f.onTrade(false, 400, T0 + 2);
        assertEquals(-0.25, f.tradeImbalance(), 1e-12);        // (200-400)/800
        assertEquals(3, f.tradeCount());
    }

    @Test
    void oldTradesFadeFromTheImbalance() {
        long halfLife = 1_000_000L;
        FlowSignals f = new FlowSignals(halfLife);
        f.onTrade(true, 1000, T0);
        // Ten half-lives later the old buy is ~1/1024 of the new sell.
        f.onTrade(false, 1000, T0 + 10 * halfLife);
        assertTrue(f.tradeImbalance() < -0.99,
                "stale flow must fade: " + f.tradeImbalance());
    }

    @Test
    void oneSidedQuotesAreASignalGapNotMaximalPressure() {
        FlowSignals f = new FlowSignals(Long.MAX_VALUE / 2);
        f.onQuote(100, 500, 101, 400, T0);
        f.onQuote(100, 700, 101, 400, T0 + 1);                 // OFI = +200
        assertEquals(200, f.ofi(), 1e-9);

        // The last offering venue drops (Nbbo sentinel): queue imbalance must
        // read 0 (documented), and OFI must NOT book a fake buy sweep.
        f.onQuote(100, 700, Integer.MAX_VALUE, 0, T0 + 2);
        assertEquals(0, f.queueImbalance(), 1e-12);
        assertEquals(200, f.ofi(), 1e-9, "a sentinel is not order flow");

        // The book re-forms: baseline re-seeds, no phantom contribution.
        f.onQuote(100, 700, 101, 300, T0 + 3);
        assertEquals(200, f.ofi(), 1e-9);
        // ...and flow accrues normally from the new baseline.
        f.onQuote(100, 900, 101, 300, T0 + 4);
        assertEquals(400, f.ofi(), 1e-9);
        // Bid-side-only emptiness mirrors.
        f.onQuote(Integer.MIN_VALUE, 0, 101, 300, T0 + 5);
        assertEquals(0, f.queueImbalance(), 1e-12);
    }

    @Test
    void steadyStateSignalsAreAllocationFree() {
        FlowSignals f = new FlowSignals();
        for (int i = 0; i < 200_000; i++) {                    // warm-up
            step(f, i);
        }
        var mx = (com.sun.management.ThreadMXBean)
                java.lang.management.ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long before = mx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < 500_000; i++) {
            step(f, i);
        }
        long allocated = mx.getThreadAllocatedBytes(tid) - before;
        assertTrue(allocated < 100_000, "signals allocated " + allocated + " bytes");
    }

    private static void step(FlowSignals f, int i) {
        long t = T0 + i * 1_000L;
        f.onQuote(100 + (i % 3), 100 + (i % 7) * 50, 102 + (i % 2), 100 + (i % 5) * 40, t);
        if ((i & 3) == 0) {
            f.onTrade((i & 4) == 0, 10 + (i % 90), t);
        }
    }
}
