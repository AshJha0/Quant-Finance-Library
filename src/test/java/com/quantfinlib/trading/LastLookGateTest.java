package com.quantfinlib.trading;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LastLookGateTest {

    private static final double PIP = 0.0001;

    @Test
    void withinToleranceAlwaysFills() {
        LastLookGate g = new LastLookGate(PIP);
        assertTrue(g.accept(true, 1.08500, 1.08505));      // half a pip
        assertTrue(g.accept(true, 1.08500, 1.08495));
        assertTrue(g.accept(false, 1.08500, 1.08510));     // exactly at tolerance
        assertEquals(3, g.accepts());
        assertEquals(0, g.rejects());
    }

    @Test
    void symmetricRejectionInBothDirections() {
        LastLookGate g = new LastLookGate(PIP);
        // Maker sells; fair jumps 3 pips against the maker: classic pick-off.
        assertFalse(g.accept(true, 1.08500, 1.08530));
        assertEquals(1, g.makerProtectiveRejects());
        // Maker sells; fair DROPS 3 pips (maker would love this fill):
        // a symmetric gate rejects anyway — that's the Code's requirement.
        assertFalse(g.accept(true, 1.08500, 1.08470));
        assertEquals(1, g.takerProtectiveRejects());
        // Maker buys mirror.
        assertFalse(g.accept(false, 1.08500, 1.08470));    // fair fell: hurts buyer-maker
        assertEquals(2, g.makerProtectiveRejects());
        assertFalse(g.accept(false, 1.08500, 1.08530));
        assertEquals(2, g.takerProtectiveRejects());
        assertEquals(1.0, g.rejectRate(), 1e-12);
    }

    @Test
    void statisticsSplitEvenlyUnderSymmetricNoise() {
        LastLookGate g = new LastLookGate(PIP);
        java.util.SplittableRandom rnd = new java.util.SplittableRandom(3);
        for (int i = 0; i < 100_000; i++) {
            double move = (rnd.nextDouble() - 0.5) * 6 * PIP;   // symmetric moves
            g.accept(rnd.nextBoolean(), 1.08500, 1.08500 + move);
        }
        assertTrue(g.rejects() > 0);
        double asymmetry = (double) g.makerProtectiveRejects() / g.rejects();
        assertTrue(Math.abs(asymmetry - 0.5) < 0.02,
                "symmetric last look must reject both directions equally: " + asymmetry);
    }

    @Test
    void decisionsAreAllocationFree() {
        LastLookGate g = new LastLookGate(PIP);
        for (int i = 0; i < 200_000; i++) {                     // warm-up
            g.accept((i & 1) == 0, 1.08500, 1.08500 + (i % 5 - 2) * PIP);
        }
        var mx = (com.sun.management.ThreadMXBean)
                java.lang.management.ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long before = mx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < 500_000; i++) {
            g.accept((i & 1) == 0, 1.08500, 1.08500 + (i % 5 - 2) * PIP);
        }
        long allocated = mx.getThreadAllocatedBytes(tid) - before;
        assertTrue(allocated < 100_000, "gate allocated " + allocated + " bytes");
    }
}
