package com.quantfinlib.microstructure;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Round 6: self-exciting intensity + the IC-weighted alpha ensemble. */
class QuantModels6Test {

    private static final long SEC = 1_000_000_000L;

    // ------------------------------------------------------------------
    // HawkesIntensity — activity breeds activity
    // ------------------------------------------------------------------

    @Test
    void steadyFlowReadsAsBaselineNotAsABurst() {
        HawkesIntensity h = new HawkesIntensity(2.0, 0.1, 2 * SEC);
        long t = 0;
        for (int i = 0; i < 200; i++) {
            t += SEC / 2;                                  // exactly baseline pace
            h.onEvent(t);
        }
        // Stationary excitation at baseline pace: alpha/(1 - e^(-beta/2)) / mu
        // = ~0.31 here — comfortably below a burst reading.
        assertTrue(h.burstScore(t) < 0.4,
                "baseline-pace flow is not a burst: " + h.burstScore(t));
        assertEquals(200, h.events());
    }

    @Test
    void aBurstSpikesTheScoreThenDecaysWithTheHalfLife() {
        HawkesIntensity h = new HawkesIntensity(2.0, 0.1, 2 * SEC);
        long t = 0;
        for (int i = 0; i < 40; i++) {
            t += SEC / 1000;                               // 1ms machine-gun burst
            h.onEvent(t);
        }
        double atBurst = h.burstScore(t);
        assertEquals(1.0, atBurst, 1e-9, "40 near-instant events saturate the score");
        double later = h.burstScore(t + 4 * SEC);          // two half-lives on
        assertTrue(later < atBurst && later > 0,
                "excitation fades, it does not vanish: " + later);
        assertTrue(h.intensity(t) > h.intensity(t + 60 * SEC), "intensity reverts");
        assertEquals(2.0, h.intensity(t + 600 * SEC), 1e-6, "far future = baseline");
    }

    @Test
    void outOfOrderTimestampsAreDroppedAndExplosivenessIsRejected() {
        HawkesIntensity h = new HawkesIntensity(2.0, 0.1, 2 * SEC);
        assertEquals(2.0, h.intensity(0), 1e-12, "baseline before any event");
        h.onEvent(SEC);
        h.onEvent(SEC / 2);                                // feed-merge jitter
        assertEquals(1, h.events(), "backwards time never becomes negative decay");
        // Branching ratio alpha/beta >= 1 means every event spawns >= 1 child.
        assertThrows(IllegalArgumentException.class,
                () -> new HawkesIntensity(2.0, 0.5, 2 * SEC));
        assertThrows(IllegalArgumentException.class,
                () -> new HawkesIntensity(0, 0.1, 2 * SEC));
    }

    // ------------------------------------------------------------------
    // AlphaEnsemble — trust is earned per component
    // ------------------------------------------------------------------

    @Test
    void weightConcentratesOnTheComponentThatPredicts() {
        AlphaEnsemble ens = new AlphaEnsemble(2, 0.01);
        Random rnd = new Random(42);
        double[] values = new double[2];
        double planted = 0;
        for (int i = 0; i < 3_000; i++) {
            // The return realized NOW was leaned by component 0's PREVIOUS value.
            double ret = 1e-4 * (0.6 * planted + rnd.nextGaussian());
            planted = rnd.nextDouble() * 2 - 1;
            values[0] = planted;                           // predictive
            values[1] = rnd.nextDouble() * 2 - 1;          // pure noise
            ens.onObservation(values, ret);
        }
        // Theoretical IC of the plant: 0.6·std(qi)/√((0.6·std)²+1) ≈ 0.33;
        // the decayed estimate sits near it.
        assertTrue(ens.componentIC(0) > 0.2, "the real signal earns IC: " + ens.componentIC(0));
        assertTrue(Math.abs(ens.componentIC(1)) < 0.15, "noise earns none");
        // Component 1 votes hard the other way — and is ignored.
        values[0] = 1;
        values[1] = -1;
        double combined = ens.combined(values);
        assertTrue(combined > 0.15, "the blend follows the trusted component: " + combined);
        assertTrue(combined <= 1);
    }

    @Test
    void aBarelyTrustedBlendIsABarelySizedSignal() {
        // The weights are NOT renormalized: with only weak trust, the output
        // must be small — never a lone IC-0.02 component at full strength.
        AlphaEnsemble ens = new AlphaEnsemble(1, 0.01);
        Random rnd = new Random(9);
        double[] v = new double[1];
        double planted = 0;
        for (int i = 0; i < 3_000; i++) {
            // Weak relation: signal-to-noise well under 0.2.
            double ret = 1e-4 * (0.1 * planted + rnd.nextGaussian());
            planted = rnd.nextDouble() * 2 - 1;
            v[0] = planted;
            ens.onObservation(v, ret);
        }
        double ic = ens.componentIC(0);
        assertTrue(ic > 0 && ic < 0.2, "weak but real: " + ic);
        v[0] = 1;
        assertEquals(ic, ens.combined(v), 1e-9,
                "output magnitude IS the demonstrated confidence");
    }

    @Test
    void silentBeforeATrackRecordAndOnPureNoise() {
        AlphaEnsemble young = new AlphaEnsemble(2, 0.01);
        double[] v = {1, 1};
        for (int i = 0; i < 50; i++) {
            young.onObservation(v, 1e-4);
        }
        assertEquals(0, young.combined(v), 0.0, "50 samples at icAlpha 0.01 is no record");

        AlphaEnsemble noise = new AlphaEnsemble(2, 0.01);
        Random rnd = new Random(7);
        double[] nv = new double[2];
        for (int i = 0; i < 3_000; i++) {
            nv[0] = rnd.nextDouble() * 2 - 1;
            nv[1] = rnd.nextDouble() * 2 - 1;
            noise.onObservation(nv, 1e-4 * rnd.nextGaussian());
        }
        nv[0] = 1;
        nv[1] = 1;
        assertTrue(Math.abs(noise.combined(nv)) < 0.15,
                "noise components carry only noise-sized weight: " + noise.combined(nv));
    }

    @Test
    void nonFiniteInputsSkipScoringButNeverPoisonOrInflate() {
        AlphaEnsemble ens = new AlphaEnsemble(2, 0.5);
        // Establish real IC history on component 0.
        ens.onObservation(new double[]{0.8, -0.1}, 0);     // first call: snapshot only
        ens.onObservation(new double[]{-0.6, 0.2}, 1e-4);
        ens.onObservation(new double[]{0.5, 0.5}, -1e-4);
        double ic0 = ens.componentIC(0);
        assertTrue(ic0 != 0 && Double.isFinite(ic0), "history established: " + ic0);
        long before = ens.samples();

        ens.onObservation(new double[]{0.7, 0.7}, Double.NaN);
        assertEquals(before, ens.samples(), "NaN return scores nothing");

        // A NaN COMPONENT in the snapshot: its scoring is skipped (history
        // preserved, not NaN-poisoned to a permanent zero), the finite
        // sibling still scores, and the observation still counts.
        ens.onObservation(new double[]{Double.NaN, 0.3}, 1e-4);   // arms NaN snapshot
        ens.onObservation(new double[]{0.1, 0.1}, 1e-4);          // scores comp1 only
        assertEquals(before + 2, ens.samples());
        assertTrue(ens.componentIC(0) != 0 && Double.isFinite(ens.componentIC(0)),
                "the skipped component keeps its evidence: " + ens.componentIC(0));

        // An observation where NOTHING scores must not inflate the record.
        ens.onObservation(new double[]{Double.NaN, Double.NaN}, 1e-4);
        long all = ens.samples();
        ens.onObservation(new double[]{0.2, 0.2}, 1e-4);   // all-NaN snapshot: no scoring
        assertEquals(all, ens.samples(), "evidence that scored nothing is not evidence");

        assertThrows(IllegalArgumentException.class, () -> new AlphaEnsemble(0));
        assertThrows(IllegalArgumentException.class,
                () -> ens.onObservation(new double[1], 0));
    }

    // ------------------------------------------------------------------
    // Allocation
    // ------------------------------------------------------------------

    @Test
    void streamingUpdatesAreAllocationFree() {
        HawkesIntensity hawkes = new HawkesIntensity(2.0, 0.1, 2 * SEC);
        AlphaEnsemble ens = new AlphaEnsemble(4, 0.01);
        double[] values = new double[4];
        double blackhole = 0;
        for (int i = 0; i < 200_000; i++) {                // warm-up
            blackhole += step(hawkes, ens, values, i);
        }
        var mx = (com.sun.management.ThreadMXBean)
                java.lang.management.ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long before = mx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < 500_000; i++) {
            blackhole += step(hawkes, ens, values, i);
        }
        long allocated = mx.getThreadAllocatedBytes(tid) - before;
        assertTrue(allocated < 100_000, "round-6 models allocated " + allocated + " bytes");
        assertTrue(blackhole != 0);
    }

    private static double step(HawkesIntensity hawkes, AlphaEnsemble ens,
                               double[] values, int i) {
        long t = i * (SEC / 4);
        hawkes.onEvent(t);
        for (int c = 0; c < values.length; c++) {
            values[c] = ((i * 31 + c * 17) % 200 - 100) / 100.0;
        }
        ens.onObservation(values, 1e-4 * (0.3 * values[0] + ((i * 13) % 7 - 3) * 0.2));
        return hawkes.burstScore(t) + hawkes.intensity(t) + ens.combined(values)
                + ens.componentIC(1);
    }
}
