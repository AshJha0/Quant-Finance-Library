package com.quantfinlib.stats;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * Pins the canonical estimators to independent references (hand-worked
 * values and the Hyndman-Fan 1996 table of definitions as R's
 * {@code quantile(type = 7)} and {@code type = 1} compute them).
 */
class StatsTest {

    private static final double[] V = {1, 2, 3, 4};

    @Test
    void momentsOnWorkedExample() {
        assertEquals(2.5, Stats.mean(V), 0);
        assertEquals(5.0 / 3, Stats.sampleVariance(V), 1e-15);
        assertEquals(1.25, Stats.populationVariance(V), 0);
        assertEquals(Math.sqrt(5.0 / 3), Stats.sampleStdDev(V), 1e-15);
        assertEquals(Math.sqrt(1.25), Stats.populationStdDev(V), 0);
        assertEquals(3.5, Stats.mean(V, 2, 4), 0);
        assertEquals(0.5, Stats.sampleVariance(V, 2, 4), 0);
        assertEquals(0.5, Stats.populationStdDev(V, 2, 4), 0);
        assertEquals(Math.sqrt(0.5), Stats.sampleStdDev(V, 2, 4), 0);
        assertEquals(0.25, Stats.populationVariance(V, 2, 4), 0);
    }

    @Test
    void edgeCases() {
        double[] empty = {};
        assertTrue(Double.isNaN(Stats.mean(empty)));
        assertTrue(Double.isNaN(Stats.populationVariance(empty)));
        assertTrue(Double.isNaN(Stats.sampleVariance(new double[] {7})));
        assertEquals(0, Stats.populationVariance(new double[] {7}), 0);
        assertTrue(Double.isNaN(Stats.quantile(empty, 0.5)));
        assertTrue(Double.isNaN(Stats.quantileType1Sorted(empty, 0.5)));
        assertTrue(Double.isNaN(Stats.median(empty)));
        assertEquals(7, Stats.median(new double[] {7}), 0);
        assertTrue(Double.isNaN(Stats.mean(new double[] {1, Double.NaN})));
        assertThrows(IllegalArgumentException.class, () -> Stats.quantile(V, 1.01));
        assertThrows(IllegalArgumentException.class, () -> Stats.quantileSorted(V, -0.1));
        assertThrows(IllegalArgumentException.class, () -> Stats.quantileType1Sorted(V, Double.NaN));
        assertThrows(IndexOutOfBoundsException.class, () -> Stats.mean(V, 3, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> Stats.mean(V, -1, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> Stats.mean(V, 0, 5));
        assertThrows(IllegalArgumentException.class, () -> Stats.sampleCovariance(V, new double[] {1}));
        assertThrows(IllegalArgumentException.class,
                () -> Stats.sampleCovariance(new double[] {1}, new double[] {1}));
        assertTrue(Double.isNaN(Stats.pearson(new double[] {1, 1, 1}, new double[] {1, 2, 3})));
    }

    @Test
    void quantileTypesMatchR() {
        // R: quantile(c(1,2,3,4), c(0, .1, .25, .5, .9, 1), type = 7)
        double[] p = {0, 0.1, 0.25, 0.5, 0.9, 1};
        double[] t7 = {1, 1.3, 1.75, 2.5, 3.7, 4};
        // R: type = 1
        double[] t1 = {1, 1, 1, 2, 4, 4};
        for (int i = 0; i < p.length; i++) {
            assertEquals(t7[i], Stats.quantile(new double[] {4, 2, 1, 3}, p[i]), 1e-15, "type 7 p=" + p[i]);
            assertEquals(t1[i], Stats.quantileType1Sorted(V, p[i]), 0, "type 1 p=" + p[i]);
        }
        assertEquals(2.5, Stats.median(new double[] {4, 1, 3, 2}), 0);
        assertEquals(3, Stats.median(new double[] {5, 1, 3}), 0);
    }

    @Test
    void covarianceAndPearson() {
        double[] a = {1, 2, 3, 4, 5};
        double[] b = {2, 4, 5, 4, 5};
        // Hand-worked: deviations a {-2,-1,0,1,2}, b {-2,0,1,0,1}; sum products 6
        assertEquals(1.5, Stats.sampleCovariance(a, b), 1e-15);
        // r = 6 / sqrt(10 * 6)
        assertEquals(6 / Math.sqrt(60), Stats.pearson(a, b), 1e-15);
    }

    @Test
    void ranksAverageTiesAndNan() {
        assertArrayEquals(new double[] {2, 3.5, 3.5, 1}, Stats.ranks(new double[] {10, 20, 20, 5}), 0);
        assertArrayEquals(new double[] {1.5, 1.5}, Stats.ranks(new double[] {-0.0, 0.0}), 0);
        assertArrayEquals(new double[] {3, 1, 4, 2},
                Stats.ranks(new double[] {Double.NaN, 1, Double.NaN, 2}), 0);
        assertArrayEquals(new double[0], Stats.ranks(new double[0]), 0);
    }

    // ---- agreement with the duplicates the canonical methods replaced ----

    /** v2.0 body of {@code risk.Dependence.ranks} (boxed index sort). */
    private static double[] legacyDependenceRanks(double[] values) {
        int n = values.length;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (x, y) -> Double.compare(values[x], values[y]));
        double[] rank = new double[n];
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && values[order[j + 1]] == values[order[i]]) {
                j++;
            }
            double mid = (i + j) / 2.0 + 1;
            for (int k = i; k <= j; k++) {
                rank[order[k]] = mid;
            }
            i = j + 1;
        }
        return rank;
    }

    /** v2.0 body of {@code alpha.Factors.quantile} (type 7, other algebraic form). */
    private static double legacyFactorsQuantile(double[] sorted, double q) {
        double h = (sorted.length - 1) * q;
        int lo = (int) Math.floor(h);
        int hi = Math.min(sorted.length - 1, lo + 1);
        return sorted[lo] + (h - lo) * (sorted[hi] - sorted[lo]);
    }

    /** v2.0 body of the private medians in ml.AnomalyDetector and regulatory.*. */
    private static double legacyMedian(double[] v) {
        double[] sorted = v.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        return n % 2 == 1 ? sorted[n / 2] : 0.5 * (sorted[n / 2 - 1] + sorted[n / 2]);
    }

    /** v2.0 body of {@code credit.ExposureProfile.pfe} after the sort. */
    private static double legacyPfe(double[] sorted, double q) {
        int k = (int) Math.ceil(q * sorted.length) - 1;
        return sorted[Math.max(k, 0)];
    }

    /** v2.0 body of {@code util.Stats.quantileSorted}. */
    private static double legacyPercentileSorted(double[] sorted, double p) {
        if (sorted.length == 0) {
            return Double.NaN;
        }
        double idx = p * (sorted.length - 1);
        int lo = (int) Math.floor(idx);
        int hi = (int) Math.ceil(idx);
        if (lo == hi) {
            return sorted[lo];
        }
        double frac = idx - lo;
        return sorted[lo] * (1 - frac) + sorted[hi] * frac;
    }

    private static double[][] samples() {
        SplittableRandom rnd = new SplittableRandom(20261101L);
        double[] gauss = new double[257];
        for (int i = 0; i < gauss.length; i++) {
            gauss[i] = rnd.nextGaussian();
        }
        double[] ties = new double[100];
        for (int i = 0; i < ties.length; i++) {
            ties[i] = rnd.nextInt(7) - 3;
        }
        return new double[][] {
            {42}, {3, 1}, {5, 5, 5}, {2, -1, 2, 7, -1, 0.5}, {-0.0, 0.0, 1}, gauss, ties,
            {1, Double.NaN, -2, Double.NaN, 3},
        };
    }

    @Test
    void ranksAgreeWithLegacyDependenceRanks() {
        for (double[] s : samples()) {
            assertArrayEquals(legacyDependenceRanks(s), Stats.ranks(s), 0, Arrays.toString(s));
        }
        assertArrayEquals(legacyDependenceRanks(new double[0]), Stats.ranks(new double[0]), 0);
    }

    @Test
    void quantilesAgreeWithLegacyCopies() {
        double[] ps = {0, 0.01, 0.05, 0.25, 0.5, 0.75, 0.95, 0.99, 1};
        for (double[] s : samples()) {
            double[] sorted = s.clone();
            Arrays.sort(sorted);
            for (double p : ps) {
                double c = Stats.quantileSorted(sorted, p);
                double legacy = legacyPercentileSorted(sorted, p);
                assertEquals(Double.doubleToLongBits(legacy), Double.doubleToLongBits(c),
                        "Stats.quantileSorted p=" + p);
                // Factors only sees finite scores (it filters first).
                if (!Double.isNaN(sorted[sorted.length - 1])) {
                    double f = legacyFactorsQuantile(sorted, p);
                    assertEquals(f, c, 1e-14 * Math.max(1, Math.abs(f)), "Factors.quantile p=" + p);
                }
                if (p > 0 && p < 1) {
                    assertEquals(Double.doubleToLongBits(legacyPfe(sorted, p)),
                            Double.doubleToLongBits(Stats.quantileType1Sorted(sorted, p)), "pfe p=" + p);
                }
            }
            assertEquals(Double.doubleToLongBits(legacyMedian(s)), Double.doubleToLongBits(Stats.median(s)),
                    "median " + Arrays.toString(s));
        }
    }

    @Test
    void momentsAgreeWithTwoPassReference() {
        for (double[] s : samples()) {
            int n = s.length;
            double sum = 0;
            for (double x : s) {
                sum += x;
            }
            double m = sum / n;
            double ss = 0;
            for (double x : s) {
                ss += (x - m) * (x - m);
            }
            assertEquals(Double.doubleToLongBits(m), Double.doubleToLongBits(Stats.mean(s)));
            assertEquals(Double.doubleToLongBits(ss / n), Double.doubleToLongBits(Stats.populationVariance(s)));
            double sample = n < 2 ? Double.NaN : ss / (n - 1);
            assertEquals(Double.doubleToLongBits(sample), Double.doubleToLongBits(Stats.sampleVariance(s)));
        }
    }
}
