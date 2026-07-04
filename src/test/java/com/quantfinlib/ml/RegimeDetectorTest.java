package com.quantfinlib.ml;

import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegimeDetectorTest {

    private static final double CALM_VOL = 0.006;
    private static final double STRESS_VOL = 0.030;

    /** Simulates persistent two-regime returns; trueRegime[t] filled in. */
    private static double[] simulate(int n, int[] trueRegime, long seed) {
        SplittableRandom rnd = new SplittableRandom(seed);
        double[] r = new double[n];
        int state = 0;
        for (int t = 0; t < n; t++) {
            if (rnd.nextDouble() < 0.02) {
                state = 1 - state;   // persistence 0.98
            }
            trueRegime[t] = state;
            r[t] = (state == 0 ? CALM_VOL : STRESS_VOL) * rnd.nextGaussian();
        }
        return r;
    }

    @Test
    void recoversVolatilitiesAndPersistence() {
        int[] truth = new int[3_000];
        double[] returns = simulate(3_000, truth, 42);
        RegimeDetector.RegimeModel model = RegimeDetector.fit(returns, 200);

        assertEquals(CALM_VOL, model.stdDevs()[0], CALM_VOL * 0.3);
        assertEquals(STRESS_VOL, model.stdDevs()[1], STRESS_VOL * 0.3);
        assertTrue(model.stdDevs()[1] > model.stdDevs()[0], "state 1 must be high vol");
        assertTrue(model.transition()[0][0] > 0.9 && model.transition()[1][1] > 0.9,
                "regimes should be persistent");
        assertTrue(model.expectedDuration(0) > 10);
        assertTrue(Double.isFinite(model.logLikelihood()));
    }

    @Test
    void smoothedProbabilitiesClassifyRegimesAccurately() {
        int[] truth = new int[3_000];
        double[] returns = simulate(3_000, truth, 7);
        RegimeDetector.RegimeModel model = RegimeDetector.fit(returns, 200);

        int correct = 0;
        for (int t = 0; t < returns.length; t++) {
            int classified = model.smoothedHighVolProbability()[t] > 0.5 ? 1 : 0;
            if (classified == truth[t]) {
                correct++;
            }
        }
        double accuracy = (double) correct / returns.length;
        assertTrue(accuracy > 0.85, "classification accuracy " + accuracy);
    }

    @Test
    void currentRegimeTracksTheRecentState() {
        // 800 calm periods followed by 200 stressed: model must end in state 1.
        SplittableRandom rnd = new SplittableRandom(11);
        double[] returns = new double[1_000];
        for (int t = 0; t < 1_000; t++) {
            returns[t] = (t < 800 ? CALM_VOL : STRESS_VOL) * rnd.nextGaussian();
        }
        RegimeDetector.RegimeModel model = RegimeDetector.fit(returns, 200);
        assertEquals(1, model.currentRegime());
        assertTrue(model.currentProbabilities()[1] > 0.8);
        // Early sample was calm.
        assertTrue(model.smoothedHighVolProbability()[100] < 0.5);
    }

    @Test
    void rejectsShortSamples() {
        assertThrows(IllegalArgumentException.class,
                () -> RegimeDetector.fit(new double[50], 100));
    }
}
