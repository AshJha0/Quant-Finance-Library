package com.quantfinlib.microstructure;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Round 3 of the quant model layer: online alpha learning, lead-lag, day types. */
class QuantModels3Test {

    // ------------------------------------------------------------------
    // OnlineAlphaLearner — ridge-SGD weights + prequential IC
    // ------------------------------------------------------------------

    @Test
    void learnerRecoversPlantedWeightsAndReportsPositiveIC() {
        OnlineAlphaLearner learner = new OnlineAlphaLearner();
        Random rnd = new Random(42);
        for (int i = 0; i < 30_000; i++) {
            double qi = rnd.nextDouble() * 2 - 1;
            double ti = rnd.nextDouble() * 2 - 1;
            double ofi = rnd.nextDouble() * 2 - 1;
            double mz = rnd.nextDouble() * 2 - 1;
            double y = 0.6 * qi - 0.4 * mz + 0.1 * rnd.nextGaussian();
            learner.train(qi, ti, ofi, mz, y);
        }
        assertEquals(0.6, learner.weight(0), 0.05, "queue-imbalance weight recovered");
        assertEquals(-0.4, learner.weight(3), 0.05, "momentum weight recovered");
        assertTrue(Math.abs(learner.weight(1)) < 0.05, "no phantom trade-imbalance weight");
        assertTrue(Math.abs(learner.weight(2)) < 0.05, "no phantom OFI weight");
        assertTrue(learner.outOfSampleIC() > 0.85,
                "prequential IC sees the signal: " + learner.outOfSampleIC());
        assertEquals(30_000, learner.samples());
    }

    @Test
    void pureNoiseTargetCannotMasqueradeAsValidated() {
        OnlineAlphaLearner learner = new OnlineAlphaLearner();
        Random rnd = new Random(7);
        for (int i = 0; i < 30_000; i++) {
            // The target is independent noise: there is nothing to learn.
            learner.train(rnd.nextDouble() * 2 - 1, rnd.nextDouble() * 2 - 1,
                    rnd.nextDouble() * 2 - 1, rnd.nextDouble() * 2 - 1,
                    0.02 * rnd.nextGaussian());
        }
        assertTrue(Math.abs(learner.outOfSampleIC()) < 0.2,
                "no out-of-sample IC on noise: " + learner.outOfSampleIC());
        for (int f = 0; f < 4; f++) {
            assertTrue(Math.abs(learner.weight(f)) < 0.05,
                    "ridge keeps noise weights near zero, w" + f + "=" + learner.weight(f));
        }
    }

    @Test
    void fxScaleReturnsLearnJustAsWell() {
        // FX intraday returns live around 1e-5..1e-4; the IC is
        // scale-invariant and the weights simply pick up the scale.
        OnlineAlphaLearner learner = new OnlineAlphaLearner();
        Random rnd = new Random(11);
        for (int i = 0; i < 30_000; i++) {
            double qi = rnd.nextDouble() * 2 - 1;
            double mz = rnd.nextDouble() * 2 - 1;
            double y = 1e-5 * (0.6 * qi - 0.4 * mz) + 1e-6 * rnd.nextGaussian();
            learner.train(qi, 0, 0, mz, y);
        }
        assertTrue(learner.outOfSampleIC() > 0.85, "IC is scale-free");
        assertEquals(6e-6, learner.weight(0), 1e-6, "weights absorb the return scale");
    }

    @Test
    void nanInputsNeverPoisonWeightsOrIC() {
        OnlineAlphaLearner learner = new OnlineAlphaLearner();
        learner.train(Double.NaN, 0, 0, 0, 0.01);
        learner.train(0.5, Double.POSITIVE_INFINITY, 0, 0, 0.01);
        learner.train(0.5, 0, 0, 0, Double.NaN);
        assertEquals(0, learner.samples(), "garbage samples are skipped entirely");
        assertEquals(0, learner.weight(0), 0.0);
        assertEquals(0, learner.outOfSampleIC(), 0.0);
    }

    @Test
    void aLuckyFirstHourIsNotATrackRecord() {
        // Even a perfect early IC emits no signal until the learner has one
        // full IC memory (~1/icAlpha samples) of evidence.
        OnlineAlphaLearner learner = new OnlineAlphaLearner();
        Random rnd = new Random(17);
        for (int i = 0; i < 50; i++) {
            double qi = rnd.nextDouble() * 2 - 1;
            learner.train(qi, 0, 0, 0, 0.5 * qi);          // noiseless: IC -> 1 fast
        }
        assertTrue(learner.outOfSampleIC() > 0, "the IC itself reads positive");
        assertEquals(0, learner.normalizedPrediction(1, 0, 0, 0), 0.0,
                "but 50 samples at icAlpha 0.01 is not yet evidence");
        for (int i = 0; i < 150; i++) {
            double qi = rnd.nextDouble() * 2 - 1;
            learner.train(qi, 0, 0, 0, 0.5 * qi);
        }
        assertTrue(learner.normalizedPrediction(1, 0, 0, 0) > 0,
                "past one IC memory the demonstrated signal flows");
    }

    @Test
    void trainFromFitsTheSnapshotNotTheContemporaneousFeatures() {
        // The lookahead trap: at t+1 the engine's features already contain
        // the (t, t+1] move. trainFrom must fit the return against the
        // features snapshotted at t, not the ones visible now.
        SignalEngine sig = new SignalEngine(1);
        OnlineAlphaLearner learner = new OnlineAlphaLearner(0.1, 0, 0.01);

        // State at t: heavy bid queue -> queueImbalance strongly POSITIVE.
        sig.onQuote(0, 100.00, 900, 100.02, 100, 1_000_000L);
        learner.trainFrom(sig, 0, 0.0);                     // first call: snapshot only
        assertEquals(0, learner.samples(), "nothing aligned to train on yet");

        // State at t+1: the book flipped -> queueImbalance strongly NEGATIVE,
        // and the interval's return was positive.
        sig.onQuote(0, 100.00, 100, 100.02, 900, 2_000_000L);
        learner.trainFrom(sig, 0, +1.0);
        assertEquals(1, learner.samples());
        assertTrue(learner.weight(0) > 0,
                "the positive return was fitted to the POSITIVE snapshot imbalance, "
                        + "not the negative contemporaneous one: w0=" + learner.weight(0));
    }

    @Test
    void normalizedPredictionIsGatedOnDemonstratedIC() {
        // Fresh learner: no demonstrated predictive power -> no signal.
        assertEquals(0, new OnlineAlphaLearner().normalizedPrediction(1, 1, 1, 1), 0.0);

        OnlineAlphaLearner learner = new OnlineAlphaLearner();
        Random rnd = new Random(3);
        for (int i = 0; i < 30_000; i++) {
            double qi = rnd.nextDouble() * 2 - 1;
            learner.train(qi, 0, 0, 0, 0.5 * qi + 0.05 * rnd.nextGaussian());
        }
        double strong = learner.normalizedPrediction(1, 0, 0, 0);
        double opposite = learner.normalizedPrediction(-1, 0, 0, 0);
        assertTrue(strong > 0.5, "strong aligned features -> strong signal: " + strong);
        assertTrue(strong <= 1.0, "MarketState.alpha-ready: clamped to [-1,1]");
        assertTrue(opposite < -0.5, "sign follows the features");
    }

    // ------------------------------------------------------------------
    // LeadLagEstimator — planted lead recovery
    // ------------------------------------------------------------------

    @Test
    void recoversAPlantedThreeIntervalLead() {
        // EURUSD-leads-EURJPY structure: the follower echoes the leader
        // three sampling intervals later.
        LeadLagEstimator ll = new LeadLagEstimator(10, 0.01);
        Random rnd = new Random(42);
        double[] hist = new double[4];
        for (int i = 0; i < 5_000; i++) {
            double lead = 1e-4 * rnd.nextGaussian();
            System.arraycopy(hist, 0, hist, 1, hist.length - 1);
            hist[0] = lead;
            double follow = 0.8 * hist[3] + 2e-5 * rnd.nextGaussian();
            ll.onSample(lead, follow);
        }
        assertEquals(3, ll.bestLag(), "the planted lead is found");
        assertTrue(ll.bestCorrelation() > 0.6,
                "and it is strong: " + ll.bestCorrelation());
        assertTrue(Math.abs(ll.correlationAtLag(0)) < 0.2,
                "no contemporaneous relation was planted");
    }

    @Test
    void contemporaneousComoveIsNotMistakenForALead() {
        LeadLagEstimator ll = new LeadLagEstimator(10, 0.01);
        Random rnd = new Random(9);
        for (int i = 0; i < 5_000; i++) {
            double r = 1e-4 * rnd.nextGaussian();
            ll.onSample(r, r + 1e-5 * rnd.nextGaussian());
        }
        assertTrue(ll.correlationAtLag(0) > 0.9, "the pair co-moves");
        assertTrue(Math.abs(ll.bestCorrelation()) < 0.3,
                "but nobody is ahead: " + ll.bestCorrelation());
    }

    @Test
    void expectedFollowerReturnPredictsFromTheLeadersRecentMove() {
        LeadLagEstimator ll = new LeadLagEstimator(5, 0.01);
        Random rnd = new Random(21);
        double[] hist = new double[3];
        for (int i = 0; i < 5_000; i++) {
            double lead = 1e-4 * rnd.nextGaussian();
            System.arraycopy(hist, 0, hist, 1, hist.length - 1);
            hist[0] = lead;
            ll.onSample(lead, 0.5 * hist[2] + 1e-5 * rnd.nextGaussian());
        }
        assertEquals(2, ll.bestLag());
        // The leader jumps, then one quiet interval passes: the follower's
        // NEXT interval should echo the jump (lag 2 = jump interval + 1).
        ll.onSample(2e-4, 0);
        ll.onSample(0, 0);
        double expected = ll.expectedFollowerReturn();
        assertTrue(expected > 5e-5, "beta ~0.5 x 2e-4 jump: " + expected);
    }

    @Test
    void leadLagGapsAreDroppedNotPoison() {
        LeadLagEstimator ll = new LeadLagEstimator(5, 0.05);
        ll.onSample(1e-4, 1e-4);
        ll.onSample(Double.NaN, 1e-4);
        ll.onSample(1e-4, Double.POSITIVE_INFINITY);
        assertEquals(1, ll.samples(), "bad prints never enter the ring");
        ll.onSample(-1e-4, -1e-4);
        assertTrue(Double.isFinite(ll.correlationAtLag(0)));
        assertTrue(Double.isFinite(ll.expectedFollowerReturn()));
    }

    // ------------------------------------------------------------------
    // DayTypeProfiles — expiry days get their own curve
    // ------------------------------------------------------------------

    @Test
    void dayTypesLearnIndependentShapes() {
        // 0 = regular, 1 = expiry. Regular days are flat; expiry days
        // trade 3x into the close. One averaged curve would be wrong on
        // both; per-type curves are right on each.
        var volume = new DayTypeProfiles<>(2, () -> new VolumeCurve(2, 0.5));

        VolumeCurve regular = volume.profile(0);
        regular.onVolume(0, 100);
        regular.onVolume(1, 100);
        regular.rollDay();

        VolumeCurve expiry = volume.profile(1);
        expiry.onVolume(0, 100);
        expiry.onVolume(1, 300);
        expiry.rollDay();

        assertEquals(100, volume.profile(0).profileVolume(1), 1e-9,
                "the regular curve never saw the expiry close");
        assertEquals(300, volume.profile(1).profileVolume(1), 1e-9,
                "the expiry curve learned its own close");
        assertEquals(1, volume.profile(0).daysLearned());
        // Halfway through bucket 1, expiry expects far more of the day done.
        assertTrue(volume.profile(1).expectedFractionElapsed(0, 1.0)
                        < volume.profile(0).expectedFractionElapsed(0, 1.0),
                "expiry back-loads the day");
    }

    @Test
    void dayTypeFactoryVariantsAndValidation() {
        // The IntFunction variant lets a rare type seed from the regular shape.
        double[] regularShape = {100, 100};
        var vols = new DayTypeProfiles<VolumeCurve>(2,
                t -> new VolumeCurve(2, 0.5).seedProfile(regularShape));
        assertEquals(2, vols.dayTypes());
        assertEquals(100, vols.profile(1).profileVolume(0), 1e-9, "seeded, not cold");
        assertThrows(IllegalArgumentException.class,
                () -> new DayTypeProfiles<>(0, VolumeCurve::new));
    }

    // ------------------------------------------------------------------
    // Validation + allocation
    // ------------------------------------------------------------------

    @Test
    void validation() {
        assertThrows(IllegalArgumentException.class, () -> new OnlineAlphaLearner(0, 1e-4, 0.01));
        assertThrows(IllegalArgumentException.class, () -> new OnlineAlphaLearner(0.01, -1, 0.01));
        assertThrows(IllegalArgumentException.class, () -> new OnlineAlphaLearner(0.01, 1e-4, 0));
        assertThrows(IllegalArgumentException.class, () -> new LeadLagEstimator(0, 0.01));
        assertThrows(IllegalArgumentException.class, () -> new LeadLagEstimator(5, 0));
    }

    @Test
    void streamingUpdatesAreAllocationFree() {
        OnlineAlphaLearner learner = new OnlineAlphaLearner();
        LeadLagEstimator ll = new LeadLagEstimator(10, 0.01);
        double blackhole = 0;
        for (int i = 0; i < 200_000; i++) {            // warm-up
            blackhole += step(learner, ll, i);
        }
        var mx = (com.sun.management.ThreadMXBean)
                java.lang.management.ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long before = mx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < 500_000; i++) {
            blackhole += step(learner, ll, i);
        }
        long allocated = mx.getThreadAllocatedBytes(tid) - before;
        assertTrue(allocated < 100_000, "round-3 models allocated " + allocated + " bytes");
        assertTrue(blackhole != 0);
    }

    private static double step(OnlineAlphaLearner learner, LeadLagEstimator ll, int i) {
        double qi = ((i * 31) % 200 - 100) / 100.0;
        double mz = ((i * 17) % 200 - 100) / 100.0;
        learner.train(qi, -qi * 0.5, qi * 0.2, mz, 1e-4 * (0.5 * qi - 0.3 * mz));
        ll.onSample(1e-4 * qi, 1e-4 * mz);
        return learner.predict(qi, 0, 0, mz)
                + learner.normalizedPrediction(qi, 0, 0, mz)
                + ll.correlationAtLag(3) + ll.expectedFollowerReturn();
    }
}
