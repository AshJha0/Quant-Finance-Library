package com.quantfinlib.volatility;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Degenerate-bar pins for the range estimators: a market that never
 * moves has EXACTLY zero volatility under all four formulas, and every
 * estimator is scale-invariant (built entirely from log price RATIOS).
 */
class RangeVolatilityEdgeTest {

    private static final double PPY = 252;

    @Test
    void constantPriceBarsHaveExactlyZeroVolUnderEveryEstimator() {
        // O = H = L = C = 100 for every bar: every log ratio is ln(1) = 0,
        // both Yang-Zhang sample variances are 0/0-free zeros, so all four
        // estimators must return 0.0 exactly — no tolerance.
        int n = 5;
        double[] o = fill(n, 100);
        double[] h = fill(n, 100);
        double[] l = fill(n, 100);
        double[] c = fill(n, 100);
        assertEquals(0.0, RangeVolatility.parkinson(h, l, PPY), 0.0);
        assertEquals(0.0, RangeVolatility.garmanKlass(o, h, l, c, PPY), 0.0);
        assertEquals(0.0, RangeVolatility.rogersSatchell(o, h, l, c, PPY), 0.0);
        assertEquals(0.0, RangeVolatility.yangZhang(o, h, l, c, PPY), 0.0);
    }

    @Test
    void estimatorsAreScaleInvariant() {
        // Volatility is about RATIOS: quoting the same bars in cents
        // instead of dollars (scale by 4 — a power of two, so the division
        // h/l is bit-identical) cannot change any estimate.
        double[] o = {100, 104, 101, 103};
        double[] h = {108, 107, 106, 105};
        double[] l = {99, 100, 98, 101};
        double[] c = {104, 101, 105, 102};
        double[] o4 = scale(o);
        double[] h4 = scale(h);
        double[] l4 = scale(l);
        double[] c4 = scale(c);
        assertEquals(RangeVolatility.parkinson(h, l, PPY),
                RangeVolatility.parkinson(h4, l4, PPY), 0.0);
        assertEquals(RangeVolatility.garmanKlass(o, h, l, c, PPY),
                RangeVolatility.garmanKlass(o4, h4, l4, c4, PPY), 0.0);
        assertEquals(RangeVolatility.rogersSatchell(o, h, l, c, PPY),
                RangeVolatility.rogersSatchell(o4, h4, l4, c4, PPY), 0.0);
        assertEquals(RangeVolatility.yangZhang(o, h, l, c, PPY),
                RangeVolatility.yangZhang(o4, h4, l4, c4, PPY), 0.0);
    }

    private static double[] fill(int n, double v) {
        double[] a = new double[n];
        java.util.Arrays.fill(a, v);
        return a;
    }

    private static double[] scale(double[] a) {
        double[] out = new double[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = 4 * a[i];
        }
        return out;
    }
}
