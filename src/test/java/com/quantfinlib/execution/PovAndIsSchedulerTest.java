package com.quantfinlib.execution;

import com.quantfinlib.microstructure.AlmgrenChriss;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PovAndIsSchedulerTest {

    // ------------------------------------------------------------------
    // POV
    // ------------------------------------------------------------------

    @Test
    void povChasesTargetParticipation() {
        PovTracker pov = new PovTracker(10_000, 0.10, 0, 1_000);
        assertEquals(0, pov.dueQuantity());
        pov.onMarketVolume(5_000);                  // target = 500
        assertEquals(500, pov.dueQuantity());
        pov.onExecuted(500);
        assertEquals(0, pov.dueQuantity());
        pov.onMarketVolume(3_000);                  // target = 800, executed 500
        assertEquals(300, pov.dueQuantity());
        assertEquals(0.0625, pov.realizedParticipation(), 1e-12);
    }

    @Test
    void povRespectsSliceBoundsAndParentRemainder() {
        PovTracker pov = new PovTracker(1_000, 0.5, 100, 400);
        pov.onMarketVolume(150);                    // behind by 75 < minSlice
        assertEquals(0, pov.dueQuantity());
        pov.onMarketVolume(1_850);                  // behind by 1000 -> capped at 400
        assertEquals(400, pov.dueQuantity());
        pov.onExecuted(900);
        pov.onMarketVolume(10_000);                 // behind, but only 100 left
        assertEquals(100, pov.dueQuantity());
        pov.onExecuted(100);
        assertTrue(pov.done());
        pov.onMarketVolume(10_000);
        assertEquals(0, pov.dueQuantity());
    }

    @Test
    void povValidatesItsParameters() {
        assertThrows(IllegalArgumentException.class, () -> new PovTracker(0, 0.1, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> new PovTracker(100, 0, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> new PovTracker(100, 1.5, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> new PovTracker(100, 0.1, 20, 10));
    }

    // ------------------------------------------------------------------
    // Implementation shortfall
    // ------------------------------------------------------------------

    private static AlmgrenChriss.Params params(double lambda) {
        return new AlmgrenChriss.Params(100_000, 1.0, 10, 0.5, 1e-5, 1e-6, lambda);
    }

    @Test
    void isScheduleSumsExactlyAndFrontLoadsWithUrgency() {
        List<Slice> risky = ImplementationShortfallScheduler.schedule(params(1e-4), 3_600_000);
        assertEquals(10, risky.size());
        long total = risky.stream().mapToLong(Slice::quantity).sum();
        assertEquals(100_000, total);
        // Front-loaded: first slice strictly larger than the last.
        assertTrue(risky.get(0).quantity() > risky.get(9).quantity(),
                "risk-averse IS must front-load");
        // Monotone offsets spanning the window.
        assertEquals(0, risky.get(0).offsetMillis());
        assertEquals(3_600_000 * 9 / 10, risky.get(9).offsetMillis());
    }

    @Test
    void zeroRiskAversionDegradesToTwap() {
        List<Slice> twap = ImplementationShortfallScheduler.schedule(params(0), 1_000_000);
        long first = twap.get(0).quantity();
        for (Slice s : twap) {
            assertTrue(Math.abs(s.quantity() - first) <= 1,
                    "lambda=0 must be a flat schedule");
        }
    }

    @Test
    void frontLoadCalibrationHitsTheRequestedFraction() {
        AlmgrenChriss.Params base = params(0);
        double lambda = ImplementationShortfallScheduler.riskAversionForFrontLoad(base, 0.30);
        AlmgrenChriss.Trajectory t =
                AlmgrenChriss.optimalTrajectory(base.withRiskAversion(lambda));
        assertEquals(0.30, t.trades()[0] / 100_000, 1e-3);
        assertThrows(IllegalArgumentException.class,
                () -> ImplementationShortfallScheduler.riskAversionForFrontLoad(base, 0.05));
    }

    @Test
    void sinhOverflowFailsLoudlyInsteadOfSilentlyDegrading() {
        // A huge lambda overflows sinh in the AC trajectory (NaN holdings):
        // kappa*T ~ 840 here, past sinh's ~710 overflow point. schedule()
        // must throw, not spin O(parentShares) emitting a garbage schedule.
        assertThrows(IllegalArgumentException.class,
                () -> ImplementationShortfallScheduler.schedule(params(1e40), 1_000_000));
        // The calibrator, by contrast, must survive probing overflowing
        // lambdas (NaN reads as "front-loads more than enough") and still
        // land on the requested fraction.
        double lambda = ImplementationShortfallScheduler
                .riskAversionForFrontLoad(params(0), 0.90);
        AlmgrenChriss.Trajectory t = AlmgrenChriss
                .optimalTrajectory(params(0).withRiskAversion(lambda));
        assertEquals(0.90, t.trades()[0] / 100_000, 1e-2);
    }

    @Test
    void povTrackerIsAllocationFree() {
        PovTracker pov = new PovTracker(Long.MAX_VALUE / 4, 0.1, 10, 1_000);
        for (int i = 0; i < 200_000; i++) {            // warm-up
            povStep(pov, i);
        }
        var mx = (com.sun.management.ThreadMXBean)
                java.lang.management.ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long before = mx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < 500_000; i++) {
            povStep(pov, i);
        }
        long allocated = mx.getThreadAllocatedBytes(tid) - before;
        assertTrue(allocated < 100_000, "POV allocated " + allocated + " bytes");
    }

    private static void povStep(PovTracker pov, int i) {
        pov.onMarketVolume(10 + (i % 90));
        long due = pov.dueQuantity();
        if (due > 0) {
            pov.onExecuted(due);
        }
    }
}
