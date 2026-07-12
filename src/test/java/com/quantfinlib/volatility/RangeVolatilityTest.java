package com.quantfinlib.volatility;

import com.quantfinlib.core.BarSeries;
import com.quantfinlib.util.MathUtils;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Range estimators pinned two ways: constant-range synthetic bars match
 * each estimator's closed form EXACTLY, and a discretely sampled GBM path
 * puts every estimator near the true sigma with Parkinson demonstrably
 * tighter than close-to-close (the efficiency claim, tested not asserted).
 */
class RangeVolatilityTest {

    private static final double PPY = 252;

    // ------------------------------------------------------------------ exact closed forms

    @Test
    void parkinsonMatchesItsClosedFormOnConstantRangeBars() {
        int n = 10;
        double[] h = new double[n];
        double[] l = new double[n];
        java.util.Arrays.fill(h, 105);
        java.util.Arrays.fill(l, 100);
        double lnHl = Math.log(105.0 / 100.0);
        double expected = Math.sqrt(lnHl * lnHl / (4 * Math.log(2)) * PPY);
        assertEquals(expected, RangeVolatility.parkinson(h, l, PPY), 1e-15);
    }

    @Test
    void garmanKlassAndRogersSatchellMatchTheirClosedFormsOnAConstantBar() {
        int n = 7;
        double o = 100, hi = 108, lo = 99, c = 104;
        double[] open = fill(n, o);
        double[] high = fill(n, hi);
        double[] low = fill(n, lo);
        double[] close = fill(n, c);

        double hl = Math.log(hi / lo);
        double co = Math.log(c / o);
        double gkVar = 0.5 * hl * hl - (2 * Math.log(2) - 1) * co * co;
        assertEquals(Math.sqrt(gkVar * PPY),
                RangeVolatility.garmanKlass(open, high, low, close, PPY), 1e-15);

        double rsVar = Math.log(hi / c) * Math.log(hi / o) + Math.log(lo / c) * Math.log(lo / o);
        assertEquals(Math.sqrt(rsVar * PPY),
                RangeVolatility.rogersSatchell(open, high, low, close, PPY), 1e-15);
    }

    @Test
    void yangZhangCollapsesToWeightedRogersSatchellWithoutGapsOrDrift() {
        // O_i = C_{i-1} (no overnight gap) and C_i = O_i (no open-to-close
        // move): both sample variances are exactly zero, so YZ^2 must be
        // exactly (1 - k) * RS^2 — the k weighting pinned by hand.
        int n = 5;                       // m = 4 estimation periods
        double[] open = fill(n, 100);
        double[] high = fill(n, 110);
        double[] low = fill(n, 95);
        double[] close = fill(n, 100);
        double rs = Math.log(110.0 / 100) * Math.log(110.0 / 100)
                + Math.log(95.0 / 100) * Math.log(95.0 / 100);
        int m = n - 1;
        double k = 0.34 / (1.34 + (m + 1.0) / (m - 1.0));
        assertEquals(Math.sqrt((1 - k) * rs * PPY),
                RangeVolatility.yangZhang(open, high, low, close, PPY), 1e-15);
    }

    @Test
    void barSeriesOverloadsAgreeWithTheArrayForms() {
        BarSeries.Builder b = BarSeries.builder("X");
        double[] o = {100, 104, 101}, h = {108, 107, 106}, l = {99, 100, 98}, c = {104, 101, 105};
        for (int i = 0; i < 3; i++) {
            b.add(i, o[i], h[i], l[i], c[i], 1_000);
        }
        BarSeries bars = b.build();
        assertEquals(RangeVolatility.parkinson(h, l, PPY),
                RangeVolatility.parkinson(bars, PPY), 0.0);
        assertEquals(RangeVolatility.garmanKlass(o, h, l, c, PPY),
                RangeVolatility.garmanKlass(bars, PPY), 0.0);
        assertEquals(RangeVolatility.rogersSatchell(o, h, l, c, PPY),
                RangeVolatility.rogersSatchell(bars, PPY), 0.0);
        assertEquals(RangeVolatility.yangZhang(o, h, l, c, PPY),
                RangeVolatility.yangZhang(bars, PPY), 0.0);
    }

    // ------------------------------------------------------------------ GBM sanity + efficiency

    @Test
    void gbmBarsRecoverTheTrueSigmaAndParkinsonBeatsCloseToClose() {
        // Driftless GBM at sigma = 20%, 390 intraday steps per bar (the
        // observed high/low undershoot the continuous extremes by ~1-2% of
        // vol at this sampling — tolerances leave room for that bias).
        double sigma = 0.20;
        int bars = 500, steps = 390;
        double dt = 1.0 / PPY, dts = dt / steps;
        Random rng = new Random(7);
        double[] open = new double[bars];
        double[] high = new double[bars];
        double[] low = new double[bars];
        double[] close = new double[bars];
        double p = 100;
        for (int i = 0; i < bars; i++) {
            open[i] = p;
            double hi = p, lo = p;
            for (int s = 0; s < steps; s++) {
                p *= Math.exp(-0.5 * sigma * sigma * dts
                        + sigma * Math.sqrt(dts) * rng.nextGaussian());
                hi = Math.max(hi, p);
                lo = Math.min(lo, p);
            }
            high[i] = hi;
            low[i] = lo;
            close[i] = p;
        }
        assertEquals(sigma, RangeVolatility.garmanKlass(open, high, low, close, PPY), 0.02);
        assertEquals(sigma, RangeVolatility.rogersSatchell(open, high, low, close, PPY), 0.02);
        assertEquals(sigma, RangeVolatility.yangZhang(open, high, low, close, PPY), 0.02);
        assertEquals(sigma, RangeVolatility.parkinson(high, low, PPY), 0.03);

        // Efficiency: over 25 independent 20-bar blocks the Parkinson
        // estimates scatter visibly less than close-to-close estimates —
        // the ~4.9x variance advantage shows up as a smaller sample std.
        int blocks = 25, len = bars / blocks;
        double[] park = new double[blocks];
        double[] c2c = new double[blocks];
        for (int b = 0; b < blocks; b++) {
            int from = b * len;
            double[] bh = java.util.Arrays.copyOfRange(high, from, from + len);
            double[] bl = java.util.Arrays.copyOfRange(low, from, from + len);
            park[b] = RangeVolatility.parkinson(bh, bl, PPY);
            double sum = 0;
            for (int i = from + 1; i < from + len; i++) {
                double r = Math.log(close[i] / close[i - 1]);
                sum += r * r;
            }
            c2c[b] = Math.sqrt(sum / (len - 1) * PPY);
        }
        double parkStd = MathUtils.stdDev(park);
        double c2cStd = MathUtils.stdDev(c2c);
        assertTrue(parkStd < c2cStd,
                "Parkinson std " + parkStd + " must beat close-to-close " + c2cStd);
    }

    // ------------------------------------------------------------------ gates

    @Test
    void gatesRefuseMalformedBars() {
        double[] ok = {100, 100};
        assertThrows(IllegalArgumentException.class,
                () -> RangeVolatility.parkinson(new double[]{105}, new double[]{105, 100}, PPY));
        assertThrows(IllegalArgumentException.class,     // H < L
                () -> RangeVolatility.parkinson(new double[]{99, 99}, ok, PPY));
        assertThrows(IllegalArgumentException.class,     // L <= 0
                () -> RangeVolatility.parkinson(new double[]{105, 105}, new double[]{0, 100}, PPY));
        assertThrows(IllegalArgumentException.class,     // NaN
                () -> RangeVolatility.parkinson(new double[]{Double.NaN, 105}, ok, PPY));
        assertThrows(IllegalArgumentException.class,     // ppy <= 0
                () -> RangeVolatility.parkinson(new double[]{105, 105}, ok, 0));
        assertThrows(IllegalArgumentException.class,     // close above high
                () -> RangeVolatility.garmanKlass(new double[]{100}, new double[]{101},
                        new double[]{99}, new double[]{102}, PPY));
        assertThrows(IllegalArgumentException.class,     // open below low
                () -> RangeVolatility.rogersSatchell(new double[]{98}, new double[]{101},
                        new double[]{99}, new double[]{100}, PPY));
        assertThrows(IllegalArgumentException.class,     // YZ needs >= 3 bars
                () -> RangeVolatility.yangZhang(ok, new double[]{105, 105},
                        new double[]{99, 99}, ok, PPY));
    }

    private static double[] fill(int n, double v) {
        double[] a = new double[n];
        java.util.Arrays.fill(a, v);
        return a;
    }
}
