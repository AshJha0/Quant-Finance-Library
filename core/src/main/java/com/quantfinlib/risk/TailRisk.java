package com.quantfinlib.risk;

import java.util.Arrays;

/**
 * Empirical value-at-risk and expected shortfall of a loss sample, with one
 * tail definition shared by every class in the library that reports them.
 *
 * <p>Convention: inputs are losses, positive = loss. Confidence {@code c} is
 * the one-sided level (0.975 = 97.5%). With losses sorted ascending,
 * {@code L(1) <= ... <= L(n)}:</p>
 *
 * <ul>
 *   <li><b>VaR</b> is the lower empirical quantile
 *       {@code L(ceil(c·n))}, the smallest loss whose empirical CDF reaches
 *       {@code c}.</li>
 *   <li><b>ES</b> is the Acerbi-Tasche (2002, eq. 4.4 / Prop. 4.1) estimator
 *       of the tail mean over the worst {@code w = (1−c)·n} observations,
 *       with the fractional boundary weight:
 *       <pre>  ES = ( L(n) + ... + L(n−k+1) + (w − k)·L(n−k) ) / w,   k = floor(w)</pre>
 *       It is coherent, it is the sample analogue of
 *       {@code (1/(1−c))·∫_c^1 VaR_u du}, and it never falls below VaR.
 *       When {@code w < 1} it is the worst loss.</li>
 * </ul>
 *
 * <p>Example: losses 1, 2, ..., 1000 at c = 0.975 give w = 25, VaR = 975 and
 * ES = mean(976..1000) = 988.0. The estimator that also averages the VaR
 * observation itself (26 points, 987.5) is biased low by about
 * {@code 1/(w+1)} of the tail spread, which is 17% at a 250-day window.</p>
 *
 * <p>{@code c·n} and {@code (1−c)·n} are snapped to the nearest integer when
 * within 1e-9 of it, so 0.975·1000 is treated as 975 and not 975.0000000001.
 * Results are not clamped: a sample whose quantile is a gain returns a
 * negative VaR. Callers that report "0 if a gain" clamp themselves.</p>
 *
 * <p>Reference: C. Acerbi and D. Tasche, "On the coherence of expected
 * shortfall", Journal of Banking and Finance 26 (2002) 1487-1503.</p>
 */
public final class TailRisk {

    private static final double SNAP = 1e-9;

    private TailRisk() {
    }

    /** VaR and ES of one sample, both in loss units. */
    public record Tail(double var, double expectedShortfall) {
    }

    /** VaR and ES of an unsorted loss sample. The input is not modified. */
    public static Tail of(double[] losses, double confidence) {
        double[] sorted = losses.clone();
        Arrays.sort(sorted);
        return ofSorted(sorted, confidence);
    }

    /** VaR and ES of a loss sample already sorted ascending. */
    public static Tail ofSorted(double[] sortedLosses, double confidence) {
        return new Tail(varSorted(sortedLosses, confidence),
                expectedShortfallSorted(sortedLosses, confidence));
    }

    /** Lower empirical quantile {@code L(ceil(c·n))} of an ascending sample. */
    public static double varSorted(double[] sortedLosses, double confidence) {
        int n = check(sortedLosses, confidence);
        int rank = (int) Math.ceil(snap(confidence * n));   // 1-based
        rank = Math.max(1, Math.min(n, rank));
        return sortedLosses[rank - 1];
    }

    /** Acerbi-Tasche expected shortfall of an ascending sample. */
    public static double expectedShortfallSorted(double[] sortedLosses, double confidence) {
        int n = check(sortedLosses, confidence);
        double w = snap((1 - confidence) * n);
        if (w <= 1) {
            return sortedLosses[n - 1];
        }
        int k = (int) Math.floor(w);
        double sum = 0;
        for (int i = n - k; i < n; i++) {
            sum += sortedLosses[i];
        }
        double frac = w - k;
        if (frac > 0) {
            sum += frac * sortedLosses[n - k - 1];
        }
        return sum / w;
    }

    private static int check(double[] sorted, double confidence) {
        if (!(confidence > 0 && confidence < 1)) {
            throw new IllegalArgumentException("confidence must be in (0, 1): " + confidence);
        }
        if (sorted.length == 0) {
            throw new IllegalArgumentException("empty loss sample");
        }
        for (double v : sorted) {
            if (!Double.isFinite(v)) {
                throw new IllegalArgumentException("loss sample contains " + v);
            }
        }
        return sorted.length;
    }

    private static double snap(double x) {
        double r = Math.rint(x);
        return Math.abs(x - r) <= SNAP * Math.max(1, Math.abs(x)) ? r : x;
    }
}
