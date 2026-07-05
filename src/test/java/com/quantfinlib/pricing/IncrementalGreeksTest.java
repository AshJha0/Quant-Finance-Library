package com.quantfinlib.pricing;

import com.quantfinlib.pricing.BlackScholes.OptionType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Delta-gamma tick updates: quadratic accuracy against full reprices for
 * small moves, the re-anchor signal on drift, and the zero-allocation
 * guarantee on the tick path.
 */
class IncrementalGreeksTest {

    private static final double S = 100;
    private static final double K = 105;
    private static final double R = 0.04;
    private static final double Q = 0.01;
    private static final double VOL = 0.20;
    private static final double T = 0.5;

    @Test
    void smallMovesTrackTheFullRepriceToQuadraticAccuracy() {
        IncrementalGreeks inc = new IncrementalGreeks();
        inc.reprice(OptionType.CALL, S, K, R, Q, VOL, T);
        // Anchor state reproduces the full pricer exactly.
        assertEquals(BlackScholes.price(OptionType.CALL, S, K, R, Q, VOL, T),
                inc.estimatedPrice(), 1e-12);
        assertEquals(S, inc.anchorSpot());

        // A 0.3% move: Taylor error is O(dS³·speed) — far below a cent.
        double moved = S * 1.003;
        inc.onTick(moved);
        double fullPrice = BlackScholes.price(OptionType.CALL, moved, K, R, Q, VOL, T);
        double fullDelta = BlackScholes.delta(OptionType.CALL, moved, K, R, Q, VOL, T);
        assertEquals(fullPrice, inc.estimatedPrice(), 2e-4);
        assertEquals(fullDelta, inc.estimatedDelta(), 2e-4);
        // Anchor Greeks are exposed for vega/theta risk (constant between anchors).
        assertEquals(BlackScholes.vega(S, K, R, Q, VOL, T), inc.vega(), 1e-12);
        assertTrue(inc.gamma() > 0);
    }

    @Test
    void repriceSignalFiresOnDriftAndClearsOnReanchor() {
        IncrementalGreeks inc = new IncrementalGreeks();
        inc.reprice(OptionType.PUT, S, K, R, Q, VOL, T);
        inc.onTick(S * 1.002);
        assertFalse(inc.needsReprice(S * 0.005)); // 0.2% < 0.5% drift budget
        inc.onTick(S * 1.02);
        assertTrue(inc.needsReprice(S * 0.005));  // 2% blows the budget
        // Re-anchoring at the new spot clears the signal.
        inc.reprice(OptionType.PUT, S * 1.02, K, R, Q, VOL, T);
        assertFalse(inc.needsReprice(S * 0.005));
    }

    @Test
    void tickPathIsAllocationFree() {
        IncrementalGreeks inc = new IncrementalGreeks();
        inc.reprice(OptionType.CALL, S, K, R, Q, VOL, T);
        for (int i = 0; i < 100_000; i++) { // JIT warmup
            inc.onTick(S + (i % 100) * 0.001);
        }
        var mx = (com.sun.management.ThreadMXBean)
                java.lang.management.ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long before = mx.getThreadAllocatedBytes(tid);
        double blackhole = 0;
        for (int i = 0; i < 500_000; i++) {
            inc.onTick(S + (i % 100) * 0.001);
            blackhole += inc.estimatedDelta() + inc.estimatedPrice();
        }
        long allocated = mx.getThreadAllocatedBytes(tid) - before;
        assertTrue(allocated < 100_000,
                "tick path allocated " + allocated + " bytes (blackhole " + blackhole + ")");
    }
}
