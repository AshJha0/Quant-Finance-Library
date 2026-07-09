package com.quantfinlib.risk;

/**
 * Rank-based dependence measures — what Pearson correlation misses.
 * Pearson (in {@code RiskMetrics.correlation}) measures LINEAR
 * co-movement and is wrecked by a single outlier or a nonlinear (but
 * monotone) relationship; risk work needs the rank alternatives:
 *
 * <ul>
 *   <li><b>Spearman's ρ</b> — Pearson on the RANKS: "do they move in the
 *       same order?", robust to outliers and any monotone transform.
 *       Also the correlation FRTB's P&amp;L attribution test is defined
 *       on ({@code PnlAttribution});</li>
 *   <li><b>Kendall's τ</b> — the probability a random pair is concordant
 *       minus discordant. Slower to compute (O(n²) here — fine at risk
 *       sample sizes) but with the property copula work leans on:
 *       for elliptical copulas, {@code ρ_pearson = sin(πτ/2)} inverts τ
 *       into the copula correlation without distributional damage.</li>
 * </ul>
 *
 * <p>Ties get midranks (Spearman) / count as neither concordant nor
 * discordant (Kendall τ-a — adequate for continuous return data, where
 * exact ties are measure-zero; heavy-tie categorical data wants τ-b,
 * out of scope and said so). Static, deterministic, research lane.</p>
 */
public final class Dependence {

    private Dependence() {
    }

    /** Spearman rank correlation in [-1, 1]. */
    public static double spearman(double[] a, double[] b) {
        requireSameLength(a, b);
        return RiskMetrics.correlation(ranks(a), ranks(b));
    }

    /** Kendall's τ (tau-a) in [-1, 1]; O(n²). */
    public static double kendallTau(double[] a, double[] b) {
        requireSameLength(a, b);
        int n = a.length;
        long concordant = 0;
        long discordant = 0;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double da = a[i] - a[j];
                double db = b[i] - b[j];
                double prod = da * db;
                if (prod > 0) {
                    concordant++;
                } else if (prod < 0) {
                    discordant++;
                }
                // ties: neither — tau-a convention, documented above
            }
        }
        long pairs = (long) n * (n - 1) / 2;
        return (double) (concordant - discordant) / pairs;
    }

    /** The elliptical-copula bridge: Pearson ρ implied by a Kendall τ. */
    public static double pearsonFromKendall(double tau) {
        if (!(tau >= -1 && tau <= 1)) {
            throw new IllegalArgumentException("tau must be in [-1, 1]");
        }
        return Math.sin(Math.PI * tau / 2);
    }

    /** Midranks (average rank for ties), 1-based. */
    public static double[] ranks(double[] values) {
        int n = values.length;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (x, y) -> Double.compare(values[x], values[y]));
        double[] rank = new double[n];
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && values[order[j + 1]] == values[order[i]]) {
                j++;
            }
            double mid = (i + j) / 2.0 + 1;                // 1-based midrank
            for (int k = i; k <= j; k++) {
                rank[order[k]] = mid;
            }
            i = j + 1;
        }
        return rank;
    }

    private static void requireSameLength(double[] a, double[] b) {
        if (a.length != b.length || a.length < 2) {
            throw new IllegalArgumentException("need two equal-length series of >= 2");
        }
    }
}
