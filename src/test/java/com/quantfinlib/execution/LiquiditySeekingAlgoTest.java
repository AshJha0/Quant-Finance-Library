package com.quantfinlib.execution;

import com.quantfinlib.execution.BenchmarkExecutor.MarketState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The opportunistic archetype: trade when cheap, complete regardless. */
class LiquiditySeekingAlgoTest {

    private static MarketState state(double spread, double vol, double depth,
                                     double impactBps) {
        return new BenchmarkExecutor.MarketState(100, spread, vol, depth, 0.5, 0,
                impactBps);
    }

    @Test
    void aCheapCalmMomentTriggersAnAggressiveClip() {
        LiquiditySeekingAlgo seek = new LiquiditySeekingAlgo(100_000);
        // Spread under its forecast, calm regime, low impact, 40k displayed.
        long due = seek.dueQuantity(0.3, state(0.015, 0.2, 40_000, 2), 0.02);
        assertEquals(10_000, due, "25% of displayed depth, taken now");
    }

    @Test
    void anExpensiveVolatileOrImpactfulMomentWaits() {
        LiquiditySeekingAlgo seek = new LiquiditySeekingAlgo(100_000);
        assertEquals(0, seek.dueQuantity(0.3, state(0.03, 0.2, 40_000, 2), 0.02),
                "spread 50% over forecast: wait");
        assertEquals(0, seek.dueQuantity(0.3, state(0.015, 0.8, 40_000, 2), 0.02),
                "wild regime: wait");
        assertEquals(0, seek.dueQuantity(0.3, state(0.015, 0.2, 40_000, 10), 0.02),
                "trading into 10 bps of own impact: wait");
    }

    @Test
    void theCompletionFloorMakesPatienceSafe() {
        LiquiditySeekingAlgo seek = new LiquiditySeekingAlgo(100_000);
        MarketState expensive = state(0.05, 0.9, 40_000, 20);   // never cheap
        assertEquals(0, seek.dueQuantity(0.69, expensive, 0.02), "still inside patience");
        long half = seek.dueQuantity(0.85, expensive, 0.02);
        assertEquals(50_000, half, "half the ramp -> half the remainder");
        seek.onFill(half);
        assertEquals(50_000, seek.dueQuantity(1.0, expensive, 0.02),
                "at the horizon the floor IS the remainder");
        seek.onFill(50_000);
        assertTrue(seek.done());
        assertEquals(0, seek.dueQuantity(1.0, expensive, 0.02));
    }

    @Test
    void unknowableInputsAreNeverCheapButAlwaysComplete() {
        LiquiditySeekingAlgo seek = new LiquiditySeekingAlgo(100_000);
        assertEquals(0, seek.dueQuantity(0.3, state(0.015, 0.2, 40_000, 2), Double.NaN),
                "no forecast: cheapness unknowable, no burst");
        assertEquals(0, seek.dueQuantity(0.3,
                state(Double.NaN, 0.2, 40_000, 2), 0.02), "NaN spread: same");
        assertEquals(0, seek.dueQuantity(0.3,
                state(0.015, Double.NaN, 40_000, 2), 0.02),
                "an unknowable REGIME never authorizes a burst");
        assertEquals(0, seek.dueQuantity(0.3,
                state(0.015, 0.2, 40_000, Double.NaN), 0.02),
                "an unknowable impact never authorizes one either");
        assertEquals(100_000, seek.dueQuantity(1.0,
                state(Double.NaN, Double.NaN, Double.NaN, Double.NaN), Double.NaN),
                "but the floor never depends on the market being observable");
        // Infinite displayed depth on a cheap moment: everything remaining.
        LiquiditySeekingAlgo deep = new LiquiditySeekingAlgo(5_000);
        assertEquals(5_000, deep.dueQuantity(0.1,
                state(0.015, 0.2, Double.POSITIVE_INFINITY, 0), 0.02));
        assertThrows(IllegalArgumentException.class, () -> new LiquiditySeekingAlgo(0));
        assertThrows(IllegalArgumentException.class, () -> new LiquiditySeekingAlgo.Config(
                0.1, 0.5, 5, 0.25, 1.0));
    }

    @Test
    void decisionsAreAllocationFree() {
        LiquiditySeekingAlgo seek = new LiquiditySeekingAlgo(Long.MAX_VALUE / 2);
        MarketState cheap = state(0.015, 0.2, 40_000, 2);
        MarketState rich = state(0.05, 0.8, 40_000, 20);
        long blackhole = 0;
        for (int i = 0; i < 200_000; i++) {                // warm-up
            blackhole += seek.dueQuantity((i % 100) / 100.0, (i & 1) == 0 ? cheap : rich, 0.02);
        }
        var mx = (com.sun.management.ThreadMXBean)
                java.lang.management.ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long before = mx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < 500_000; i++) {
            blackhole += seek.dueQuantity((i % 100) / 100.0, (i & 1) == 0 ? cheap : rich, 0.02);
        }
        long allocated = mx.getThreadAllocatedBytes(tid) - before;
        assertTrue(allocated < 100_000, "seek decided with " + allocated + " bytes");
        assertTrue(blackhole != 0);
    }
}
