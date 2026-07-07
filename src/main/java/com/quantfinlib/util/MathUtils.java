package com.quantfinlib.util;

import java.util.Arrays;

/**
 * Numerical primitives shared across the library. All methods are allocation-light
 * and operate on primitive arrays.
 */
public final class MathUtils {

    private MathUtils() {
    }

    public static double mean(double[] v) {
        double s = 0;
        for (double x : v) {
            s += x;
        }
        return s / v.length;
    }

    public static double mean(double[] v, int from, int to) {
        double s = 0;
        for (int i = from; i < to; i++) {
            s += v[i];
        }
        return s / (to - from);
    }

    /** Sample variance (n - 1 denominator). */
    public static double variance(double[] v) {
        if (v.length < 2) {
            return 0;
        }
        double m = mean(v), s = 0;
        for (double x : v) {
            double d = x - m;
            s += d * d;
        }
        return s / (v.length - 1);
    }

    /** Sample standard deviation. */
    public static double stdDev(double[] v) {
        return Math.sqrt(variance(v));
    }

    /** Population standard deviation over [from, to). */
    public static double stdDevP(double[] v, int from, int to) {
        int n = to - from;
        if (n < 1) {
            return 0;
        }
        double m = mean(v, from, to), s = 0;
        for (int i = from; i < to; i++) {
            double d = v[i] - m;
            s += d * d;
        }
        return Math.sqrt(s / n);
    }

    /** Sample standard deviation over [from, to). */
    public static double stdDevSample(double[] v, int from, int to) {
        int n = to - from;
        if (n < 2) {
            return 0;
        }
        double m = mean(v, from, to), s = 0;
        for (int i = from; i < to; i++) {
            double d = v[i] - m;
            s += d * d;
        }
        return Math.sqrt(s / (n - 1));
    }

    /** Linear-interpolated percentile, p in [0, 1]. Copies and sorts the input. */
    public static double percentile(double[] values, double p) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return percentileSorted(sorted, p);
    }

    /** Percentile on an already-sorted array (no copy). */
    public static double percentileSorted(double[] sorted, double p) {
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

    public static double dot(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }

    public static double[] matVec(double[][] m, double[] v) {
        double[] out = new double[m.length];
        for (int i = 0; i < m.length; i++) {
            out[i] = dot(m[i], v);
        }
        return out;
    }

    /** w' * M * w (quadratic form). */
    public static double quadraticForm(double[] w, double[][] m) {
        return dot(w, matVec(m, w));
    }

    /** Sample covariance of two equally-sized series. */
    public static double covariance(double[] a, double[] b) {
        double ma = mean(a), mb = mean(b), s = 0;
        for (int i = 0; i < a.length; i++) {
            s += (a[i] - ma) * (b[i] - mb);
        }
        return s / (a.length - 1);
    }

    public static double correlation(double[] a, double[] b) {
        double sa = stdDev(a), sb = stdDev(b);
        if (sa == 0 || sb == 0) {
            return 0;
        }
        return covariance(a, b) / (sa * sb);
    }

    /**
     * Cholesky decomposition: returns lower-triangular L with A = L * L'.
     * Adds tiny diagonal jitter if the matrix is borderline non-PSD.
     */
    public static double[][] cholesky(double[][] a) {
        int n = a.length;
        double[][] l = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j <= i; j++) {
                double sum = a[i][j];
                for (int k = 0; k < j; k++) {
                    sum -= l[i][k] * l[j][k];
                }
                if (i == j) {
                    if (sum <= 0) {
                        sum = 1e-12;
                    }
                    l[i][i] = Math.sqrt(sum);
                } else {
                    l[i][j] = sum / l[j][j];
                }
            }
        }
        return l;
    }

    /**
     * Inverse standard normal CDF (Acklam's approximation, |error| < 1.15e-9).
     */
    public static double normInv(double p) {
        if (p <= 0 || p >= 1) {
            throw new IllegalArgumentException("p must be in (0,1): " + p);
        }
        final double[] a = {-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02,
                1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00};
        final double[] b = {-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02,
                6.680131188771972e+01, -1.328068155288572e+01};
        final double[] c = {-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00,
                -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00};
        final double[] d = {7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00,
                3.754408661907416e+00};
        final double plow = 0.02425, phigh = 1 - plow;
        double q, r;
        if (p < plow) {
            q = Math.sqrt(-2 * Math.log(p));
            return (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5])
                    / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        }
        if (p > phigh) {
            q = Math.sqrt(-2 * Math.log(1 - p));
            return -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5])
                    / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        }
        q = p - 0.5;
        r = q * q;
        return (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q
                / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1);
    }

    /** Population skewness: m3 / m2^1.5. */
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

    /** Population kurtosis: m4 / m2^2 (3 for a normal distribution, not excess). */
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

    /**
     * Sorts {@code keys} ascending while permuting {@code values}
     * identically — the primitive replacement for boxing an {@code Integer[]}
     * index array through a comparator sort (no allocation beyond the
     * caller's arrays, no boxed compares). Quicksort with median-of-three
     * pivots and an insertion cutoff; NaN keys are not supported (callers
     * filter NaN before ranking/selecting).
     */
    public static void pairSort(double[] keys, int[] values) {
        if (keys.length != values.length) {
            throw new IllegalArgumentException("keys and values must align");
        }
        pairSort(keys, values, 0, keys.length - 1);
    }

    private static void pairSort(double[] k, int[] v, int lo, int hi) {
        while (hi - lo > 12) {
            // Median-of-three pivot guards against sorted-input worst cases.
            int mid = (lo + hi) >>> 1;
            if (k[mid] < k[lo]) {
                swap(k, v, lo, mid);
            }
            if (k[hi] < k[lo]) {
                swap(k, v, lo, hi);
            }
            if (k[hi] < k[mid]) {
                swap(k, v, mid, hi);
            }
            double pivot = k[mid];
            int i = lo;
            int j = hi;
            while (i <= j) {
                while (k[i] < pivot) {
                    i++;
                }
                while (k[j] > pivot) {
                    j--;
                }
                if (i <= j) {
                    swap(k, v, i, j);
                    i++;
                    j--;
                }
            }
            // Recurse into the smaller half, loop on the larger: O(log n) stack.
            if (j - lo < hi - i) {
                pairSort(k, v, lo, j);
                lo = i;
            } else {
                pairSort(k, v, i, hi);
                hi = j;
            }
        }
        for (int i = lo + 1; i <= hi; i++) {   // insertion sort for small runs
            double key = k[i];
            int val = v[i];
            int j = i - 1;
            while (j >= lo && k[j] > key) {
                k[j + 1] = k[j];
                v[j + 1] = v[j];
                j--;
            }
            k[j + 1] = key;
            v[j + 1] = val;
        }
    }

    private static void swap(double[] k, int[] v, int a, int b) {
        double tk = k[a];
        k[a] = k[b];
        k[b] = tk;
        int tv = v[a];
        v[a] = v[b];
        v[b] = tv;
    }

    /** Standard normal density. */
    public static double normPdf(double x) {
        return Math.exp(-0.5 * x * x) / Math.sqrt(2 * Math.PI);
    }

    /**
     * Standard normal CDF (Abramowitz &amp; Stegun 26.2.17, |error| &lt; 7.5e-8).
     */
    public static double normCdf(double x) {
        if (x < 0) {
            return 1 - normCdf(-x);
        }
        double t = 1 / (1 + 0.2316419 * x);
        double poly = t * (0.319381530 + t * (-0.356563782 + t * (1.781477937
                + t * (-1.821255978 + t * 1.330274429))));
        return 1 - normPdf(x) * poly;
    }

    /**
     * Solves {@code A x = b} by Gaussian elimination with partial pivoting.
     * Inputs are not modified.
     */
    public static double[] solveLinear(double[][] a, double[] b) {
        int n = b.length;
        double[][] m = new double[n][];
        double[] rhs = b.clone();
        for (int i = 0; i < n; i++) {
            m[i] = a[i].clone();
        }
        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int r = col + 1; r < n; r++) {
                if (Math.abs(m[r][col]) > Math.abs(m[pivot][col])) {
                    pivot = r;
                }
            }
            if (Math.abs(m[pivot][col]) < 1e-12) {
                throw new IllegalArgumentException("singular system at column " + col);
            }
            double[] tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp;
            double tb = rhs[col]; rhs[col] = rhs[pivot]; rhs[pivot] = tb;
            for (int r = col + 1; r < n; r++) {
                double f = m[r][col] / m[col][col];
                rhs[r] -= f * rhs[col];
                for (int c = col; c < n; c++) {
                    m[r][c] -= f * m[col][c];
                }
            }
        }
        double[] x = new double[n];
        for (int i = n - 1; i >= 0; i--) {
            double s = rhs[i];
            for (int j = i + 1; j < n; j++) {
                s -= m[i][j] * x[j];
            }
            x[i] = s / m[i][i];
        }
        return x;
    }

    /** Matrix inverse by Gauss-Jordan elimination with partial pivoting. */
    public static double[][] inverse(double[][] a) {
        int n = a.length;
        double[][] aug = new double[n][2 * n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(a[i], 0, aug[i], 0, n);
            aug[i][n + i] = 1;
        }
        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int r = col + 1; r < n; r++) {
                if (Math.abs(aug[r][col]) > Math.abs(aug[pivot][col])) {
                    pivot = r;
                }
            }
            if (Math.abs(aug[pivot][col]) < 1e-12) {
                throw new IllegalArgumentException("singular matrix at column " + col);
            }
            double[] tmp = aug[col]; aug[col] = aug[pivot]; aug[pivot] = tmp;
            double diag = aug[col][col];
            for (int c = 0; c < 2 * n; c++) {
                aug[col][c] /= diag;
            }
            for (int r = 0; r < n; r++) {
                if (r == col) {
                    continue;
                }
                double f = aug[r][col];
                for (int c = 0; c < 2 * n; c++) {
                    aug[r][c] -= f * aug[col][c];
                }
            }
        }
        double[][] inv = new double[n][n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(aug[i], n, inv[i], 0, n);
        }
        return inv;
    }

    public static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public static double[] nanArray(int n) {
        double[] a = new double[n];
        Arrays.fill(a, Double.NaN);
        return a;
    }

    private static final double LN2 = Math.log(2);

    /**
     * Exponential decay factor for a half-life over an elapsed interval:
     * {@code exp(-dt·ln2/halfLife)}; 1.0 for non-positive {@code dt}. The
     * single home for the half-life→decay conversion the streaming
     * estimators use — the ln2 factor is exactly the constant that goes
     * missing when this is re-spelled per class.
     */
    public static double decayFactor(long dtNanos, long halfLifeNanos) {
        return dtNanos <= 0 ? 1.0 : Math.exp(-dtNanos * LN2 / halfLifeNanos);
    }
}
