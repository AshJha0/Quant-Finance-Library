package com.quantfinlib.stats;

import java.util.Arrays;

/**
 * Small array and scalar helpers: clamping, NaN-filled arrays, the
 * half-life decay factor and an allocation-free paired sort.
 *
 * <p>Added in 2.4.0 as the home of these helpers from
 * {@code util.MathUtils} (removed in 3.0); behaviour is unchanged.</p>
 *
 * @since 2.4.0
 */
public final class Numerics {

    private Numerics() {
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

    /** {@code max(lo, min(hi, v))}; NaN in gives NaN out, and {@code lo > hi} is not checked. */
    public static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** A new array of length {@code n} filled with NaN. */
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
