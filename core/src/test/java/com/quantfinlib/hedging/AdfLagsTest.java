package com.quantfinlib.hedging;

import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdfLagsTest {

    @Test
    void mackinnonResponseSurface() {
        // 5%, T = 100: -3.33613 - 6.1101/100 - 6.823/100² = -3.39792...
        assertEquals(-3.33613 - 0.061101 - 0.0006823, CointegrationTest.criticalValue(0.05, 100), 1e-12);
        assertTrue(CointegrationTest.criticalValue(0.01, 50) < CointegrationTest.CRITICAL_1PCT);
    }

    /** With an AR(2) spread, the augmented test recovers power the plain DF loses to serial correlation. */
    @Test
    void augmentedStatisticOnAnAr2Spread() {
        SplittableRandom rnd = new SplittableRandom(1);
        int n = 600;
        double[] b = new double[n];
        double[] a = new double[n];
        double e1 = 0, e2 = 0, level = 100;
        for (int t = 0; t < n; t++) {
            level += rnd.nextGaussian();
            double e = 1.2 * e1 - 0.3 * e2 + rnd.nextGaussian();   // roots inside: stationary
            e2 = e1;
            e1 = e;
            b[t] = level;
            a[t] = 2 + 1.5 * level + e;
        }
        CointegrationTest.EngleGrangerResult r = CointegrationTest.engleGranger(a, b, 2);
        assertEquals(1.5, r.hedgeRatio(), 0.06);
        assertTrue(r.cointegrated5pct(), "t = " + r.adfTStatistic());
        // Lag 0 reproduces the original statistic.
        assertEquals(CointegrationTest.adfTStatistic(a), CointegrationTest.adfTStatistic(a, 0), 0);
    }
}
