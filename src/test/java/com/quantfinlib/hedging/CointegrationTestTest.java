package com.quantfinlib.hedging;

import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CointegrationTestTest {

    @Test
    void detectsTrueCointegration() {
        // A = 10 + 1.5*B + stationary AR(1) spread: cointegrated by construction.
        SplittableRandom rnd = new SplittableRandom(17);
        int n = 1_000;
        double[] b = new double[n];
        double[] a = new double[n];
        b[0] = 100;
        double spread = 0;
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                b[i] = b[i - 1] + rnd.nextGaussian();
            }
            spread = 0.8 * spread + 0.5 * rnd.nextGaussian();
            a[i] = 10 + 1.5 * b[i] + spread;
        }
        CointegrationTest.EngleGrangerResult r = CointegrationTest.engleGranger(a, b);

        assertEquals(1.5, r.hedgeRatio(), 0.05);
        assertTrue(r.adfTStatistic() < CointegrationTest.CRITICAL_1PCT,
                "adf t=" + r.adfTStatistic());
        assertTrue(r.cointegrated1pct() && r.cointegrated5pct() && r.cointegrated10pct());
    }

    @Test
    void independentRandomWalksAreNotCointegrated() {
        SplittableRandom rnd = new SplittableRandom(23);
        int n = 1_000;
        double[] a = new double[n];
        double[] b = new double[n];
        a[0] = 100;
        b[0] = 100;
        for (int i = 1; i < n; i++) {
            a[i] = a[i - 1] + rnd.nextGaussian();
            b[i] = b[i - 1] + rnd.nextGaussian();
        }
        CointegrationTest.EngleGrangerResult r = CointegrationTest.engleGranger(a, b);
        assertFalse(r.cointegrated5pct(), "spurious cointegration: t=" + r.adfTStatistic());
    }

    @Test
    void adfIsVeryNegativeForWhiteNoiseAndMildForRandomWalk() {
        SplittableRandom rnd = new SplittableRandom(3);
        int n = 500;
        double[] noise = new double[n];
        double[] walk = new double[n];
        for (int i = 0; i < n; i++) {
            noise[i] = rnd.nextGaussian();
            walk[i] = (i == 0 ? 0 : walk[i - 1]) + rnd.nextGaussian();
        }
        double tNoise = CointegrationTest.adfTStatistic(noise);
        double tWalk = CointegrationTest.adfTStatistic(walk);
        assertTrue(tNoise < -10, "white noise t=" + tNoise);
        assertTrue(tWalk > -3, "random walk t=" + tWalk);
    }
}
