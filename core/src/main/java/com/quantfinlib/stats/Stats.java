package com.quantfinlib.stats;

import java.util.Arrays;

/**
 * Descriptive statistics on primitive arrays, with every convention named.
 *
 * <ul>
 *   <li>Variance and standard deviation come in two explicit forms:
 *       {@code sample} divides the sum of squared deviations by n - 1
 *       (the unbiased estimator of the variance) and {@code population}
 *       divides by n (the second central moment of the data).</li>
 *   <li>Quantiles follow Hyndman and Fan (1996), "Sample quantiles in
 *       statistical packages", The American Statistician 50(4), 361-365.
 *       {@link #quantile} is their type 7 (the default of R and NumPy):
 *       with h = (n - 1) p, Q(p) = x[floor h] + (h - floor h)(x[floor h + 1] - x[floor h])
 *       on the order statistics x[0..n-1]. {@link #quantileType1Sorted}
 *       is their type 1, the inverse of the empirical CDF:
 *       Q(p) = x[ceil(n p) - 1] (x[0] at p = 0).</li>
 *   <li>The median is the type 7 quantile at p = 0.5, which equals the
 *       middle order statistic for odd n and the mean of the two middle
 *       ones for even n.</li>
 *   <li>Ranks are 1-based midranks: tied values share the average of the
 *       positions they occupy (Kendall and Gibbons 1990, Rank Correlation
 *       Methods, 5th ed., chapter 3).</li>
 * </ul>
 *
 * <p>Edge cases: an empty input gives NaN for the mean, the quantiles and
 * the median; fewer than two points gives NaN for the sample variance and
 * standard deviation. NaN elements propagate through the moments (the
 * result is NaN) and sort above every number in the quantile functions,
 * as {@link Double#compare} orders them. A probability outside [0, 1] or a
 * NaN probability is rejected with {@link IllegalArgumentException}.</p>
 *
 * <p>Example: for {1, 2, 3, 4}, {@code mean} is 2.5, {@code sampleVariance}
 * is 5/3, {@code populationVariance} is 1.25, {@code quantile(v, 0.25)} is
 * 1.75 (type 7) and {@code quantileType1Sorted(v, 0.25)} is 1.</p>
 */
public final class Stats {

    private Stats() {
    }

    /** Arithmetic mean; NaN for an empty array. */
    public static double mean(double[] v) {
        return mean(v, 0, v.length);
    }

    /** Arithmetic mean of {@code v[from, to)}; NaN for an empty range. */
    public static double mean(double[] v, int from, int to) {
        checkRange(v, from, to);
        double s = 0;
        for (int i = from; i < to; i++) {
            s += v[i];
        }
        return s / (to - from);
    }

    /** Sample variance, n - 1 denominator; NaN for fewer than two points. */
    public static double sampleVariance(double[] v) {
        return sampleVariance(v, 0, v.length);
    }

    /** Sample variance of {@code v[from, to)}; NaN for fewer than two points. */
    public static double sampleVariance(double[] v, int from, int to) {
        checkRange(v, from, to);
        int n = to - from;
        return n < 2 ? Double.NaN : sumSquaredDeviations(v, from, to) / (n - 1);
    }

    /** Population variance, n denominator; NaN for an empty array. */
    public static double populationVariance(double[] v) {
        return populationVariance(v, 0, v.length);
    }

    /** Population variance of {@code v[from, to)}; NaN for an empty range. */
    public static double populationVariance(double[] v, int from, int to) {
        checkRange(v, from, to);
        int n = to - from;
        return n < 1 ? Double.NaN : sumSquaredDeviations(v, from, to) / n;
    }

    /** Square root of {@link #sampleVariance(double[])}. */
    public static double sampleStdDev(double[] v) {
        return Math.sqrt(sampleVariance(v));
    }

    /** Square root of {@link #sampleVariance(double[], int, int)}. */
    public static double sampleStdDev(double[] v, int from, int to) {
        return Math.sqrt(sampleVariance(v, from, to));
    }

    /** Square root of {@link #populationVariance(double[])}. */
    public static double populationStdDev(double[] v) {
        return Math.sqrt(populationVariance(v));
    }

    /** Square root of {@link #populationVariance(double[], int, int)}. */
    public static double populationStdDev(double[] v, int from, int to) {
        return Math.sqrt(populationVariance(v, from, to));
    }

    /**
     * Sample covariance of two equal-length series, n - 1 denominator.
     *
     * @throws IllegalArgumentException if the lengths differ or there are
     *         fewer than two observations
     */
    public static double sampleCovariance(double[] a, double[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("length mismatch: " + a.length + " vs " + b.length);
        }
        if (a.length < 2) {
            throw new IllegalArgumentException("need at least 2 observations, got " + a.length);
        }
        double ma = mean(a);
        double mb = mean(b);
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += (a[i] - ma) * (b[i] - mb);
        }
        return s / (a.length - 1);
    }

    /**
     * Pearson product-moment correlation: the sample covariance over the
     * product of the sample standard deviations (the n - 1 factors cancel).
     * NaN when either series has zero dispersion, where the coefficient is
     * undefined.
     *
     * @throws IllegalArgumentException if the lengths differ or there are
     *         fewer than two observations
     */
    public static double pearson(double[] a, double[] b) {
        double cov = sampleCovariance(a, b);
        double sa = sampleStdDev(a);
        double sb = sampleStdDev(b);
        if (sa == 0 || sb == 0) {
            return Double.NaN;
        }
        return cov / (sa * sb);
    }

    /**
     * Hyndman-Fan type 7 quantile of an unsorted sample; copies and sorts
     * the input, which is not modified.
     *
     * @param values the sample
     * @param p probability in [0, 1]
     * @return the quantile, NaN for an empty array
     */
    public static double quantile(double[] values, double p) {
        checkProbability(p);
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return quantileSorted(sorted, p);
    }

    /**
     * Hyndman-Fan type 7 quantile of a sample already sorted ascending
     * (linear interpolation between order statistics at h = (n - 1) p).
     *
     * @param sorted the sample, ascending
     * @param p probability in [0, 1]
     * @return the quantile, NaN for an empty array
     */
    public static double quantileSorted(double[] sorted, double p) {
        checkProbability(p);
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

    /**
     * Hyndman-Fan type 1 quantile (inverse empirical CDF, no interpolation)
     * of a sample already sorted ascending: the smallest order statistic
     * x with F_n(x) &gt;= p.
     *
     * @param sorted the sample, ascending
     * @param p probability in [0, 1]
     * @return the quantile, NaN for an empty array
     */
    public static double quantileType1Sorted(double[] sorted, double p) {
        checkProbability(p);
        if (sorted.length == 0) {
            return Double.NaN;
        }
        int k = (int) Math.ceil(p * sorted.length) - 1;
        return sorted[Math.max(k, 0)];
    }

    /** Median (type 7 quantile at 0.5); copies the input; NaN when empty. */
    public static double median(double[] values) {
        return quantile(values, 0.5);
    }

    /**
     * 1-based midranks: tied values share the average of their positions.
     * Values are ordered as {@link Double#compare} orders them except that
     * -0.0 and 0.0 tie; NaN entries rank above every number, each NaN on its
     * own rank in input order.
     *
     * <p>Example: {10, 20, 20, 5} ranks to {2, 3.5, 3.5, 1}.</p>
     *
     * @param values the sample (not modified)
     * @return a new array of ranks aligned with {@code values}
     */
    public static double[] ranks(double[] values) {
        int n = values.length;
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int numbers = n;
        while (numbers > 0 && Double.isNaN(sorted[numbers - 1])) {
            numbers--;
        }
        double[] rank = new double[n];
        int nextNan = numbers;
        for (int i = 0; i < n; i++) {
            double x = values[i];
            if (Double.isNaN(x)) {
                rank[i] = ++nextNan;
            } else {
                int lo = lowerBound(sorted, numbers, x);
                int hi = upperBound(sorted, numbers, x);
                rank[i] = (lo + hi - 1) / 2.0 + 1;
            }
        }
        return rank;
    }

    private static int lowerBound(double[] sorted, int len, double key) {
        int lo = 0;
        int hi = len;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted[mid] < key) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private static int upperBound(double[] sorted, int len, double key) {
        int lo = 0;
        int hi = len;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted[mid] > key) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return lo;
    }

    private static double sumSquaredDeviations(double[] v, int from, int to) {
        double m = mean(v, from, to);
        double s = 0;
        for (int i = from; i < to; i++) {
            double d = v[i] - m;
            s += d * d;
        }
        return s;
    }

    private static void checkRange(double[] v, int from, int to) {
        if (from < 0 || to > v.length || from > to) {
            throw new IndexOutOfBoundsException(
                    "range [" + from + ", " + to + ") outside length " + v.length);
        }
    }

    private static void checkProbability(double p) {
        if (!(p >= 0 && p <= 1)) {
            throw new IllegalArgumentException("p must be in [0, 1], got " + p);
        }
    }

    /**
     * Pearson correlation with a zero convention. Unlike {@link #pearson}, which returns NaN,
     * this returns 0 when either series has zero dispersion; callers such as
     * {@code risk.CorrelationMatrix} rely on that convention.
     *
     * @since 2.4.0
     */
    public static double pearsonOrZero(double[] a, double[] b) {
        double sa = sampleStdDev(a), sb = sampleStdDev(b);
        if (sa == 0 || sb == 0) {
            return 0;
        }
        return sampleCovariance(a, b) / (sa * sb);
    }

    /**
     * Population skewness m3 / m2^1.5; 0 when the data have no dispersion.
     *
     * @since 2.4.0
     */
    public static double skewness(double[] v) {
        double m = mean(v);
        double m2 = 0, m3 = 0;
        for (double x : v) {
            double d = x - m;
            m2 += d * d;
            m3 += d * d * d;
        }
        m2 /= v.length;
        m3 /= v.length;
        return m2 == 0 ? 0 : m3 / Math.pow(m2, 1.5);
    }

    /**
     * Population kurtosis m4 / m2^2 (3 for a normal distribution, not excess);
     * 0 when the data have no dispersion.
     *
     * @since 2.4.0
     */
    public static double kurtosis(double[] v) {
        double m = mean(v);
        double m2 = 0, m4 = 0;
        for (double x : v) {
            double d = x - m;
            m2 += d * d;
            m4 += d * d * d * d;
        }
        m2 /= v.length;
        m4 /= v.length;
        return m2 == 0 ? 0 : m4 / (m2 * m2);
    }
}
