package com.quantfinlib.alpha;

import com.quantfinlib.util.MathUtils;

/**
 * Fama-MacBeth cross-sectional regression — the standard answer to the
 * question the IC cannot answer: what is a factor exposure WORTH, per
 * period, in return space? Two passes:
 *
 * <ol>
 *   <li>each period, regress the cross-section of forward returns on
 *       that period's factor exposures (with intercept) — one premium
 *       estimate λ_k per factor per period;</li>
 *   <li>the factor premium is the time-series MEAN of the λ_k's, and
 *       its t-statistic uses the time-series standard error — which is
 *       the method's entire point: cross-sectional correlation between
 *       assets (the thing that wrecks a naive pooled regression's
 *       standard errors) is absorbed, because each period contributes
 *       exactly one observation per factor.</li>
 * </ol>
 *
 * <p>Reading the output: a premium with |t| &gt; 2 is priced; the
 * INTERCEPT should be near zero with |t| &lt; 2 — a significant
 * intercept says returns exist that your factors do not explain.
 * NaN entries (asset not in the cross-section that period — the
 * {@code AlphaContext} convention) are skipped per period; periods
 * with fewer assets than factors + 2 are skipped entirely and
 * counted. Plain time-series t-stats (no Newey-West correction —
 * stated, not hidden; premia autocorrelation inflates them). Static,
 * deterministic, research lane.</p>
 */
public final class FamaMacBeth {

    /**
     * @param premia        mean per-period premium per factor
     * @param tStats        time-series t-stat per factor
     * @param interceptMean mean per-period intercept (should be ~0)
     * @param interceptTStat its t-stat (significant = unexplained returns)
     * @param periodsUsed   cross-sections that had enough assets
     */
    public record Result(double[] premia, double[] tStats, double interceptMean,
                         double interceptTStat, int periodsUsed) {
    }

    private FamaMacBeth() {
    }

    /**
     * @param exposures      exposures[t][asset][factor] — the factor
     *                       loadings KNOWN at t
     * @param forwardReturns forwardReturns[t][asset] — the return
     *                       realized AFTER t (lookahead is the caller's
     *                       sin to avoid; align like {@code SignalEvaluator})
     */
    public static Result fit(double[][][] exposures, double[][] forwardReturns) {
        int periods = exposures.length;
        if (forwardReturns.length != periods || periods < 12) {
            throw new IllegalArgumentException("need >= 12 aligned periods, got " + periods);
        }
        int factors = exposures[0].length > 0 ? exposures[0][0].length : 0;
        if (factors < 1) {
            throw new IllegalArgumentException("need >= 1 factor");
        }
        int dim = factors + 1;                       // intercept first
        double[][] lambdas = new double[periods][];
        int used = 0;
        double[][] xtx = new double[dim][dim];
        double[] xty = new double[dim];
        double[] row = new double[dim];
        for (int t = 0; t < periods; t++) {
            int assets = exposures[t].length;
            if (forwardReturns[t].length != assets) {
                throw new IllegalArgumentException("period " + t + " misaligned");
            }
            for (double[] r : xtx) {
                java.util.Arrays.fill(r, 0);
            }
            java.util.Arrays.fill(xty, 0);
            int rows = 0;
            for (int a = 0; a < assets; a++) {
                if (exposures[t][a].length != factors) {
                    throw new IllegalArgumentException("period " + t + " asset " + a
                            + " has " + exposures[t][a].length + " factors, expected "
                            + factors);
                }
                double y = forwardReturns[t][a];
                // NaN = not in this cross-section (the AlphaContext
                // convention): skip. INFINITY = a data error (a broken
                // adjustment upstream): fail fast, never silent garbage.
                if (Double.isNaN(y) || hasNaN(exposures[t][a])) {
                    continue;
                }
                if (Double.isInfinite(y) || hasInfinity(exposures[t][a])) {
                    throw new IllegalArgumentException("infinite value at period " + t
                            + " asset " + a + " — a data error, not a missing name");
                }
                row[0] = 1;
                for (int k = 0; k < factors; k++) {
                    row[k + 1] = exposures[t][a][k];
                }
                for (int i = 0; i < dim; i++) {
                    for (int j = 0; j < dim; j++) {
                        xtx[i][j] += row[i] * row[j];
                    }
                    xty[i] += row[i] * y;
                }
                rows++;
            }
            if (rows < factors + 2) {
                continue;                            // too thin: skip, counted
            }
            try {
                double[] lambda = MathUtils.solveLinear(xtx, xty);
                lambdas[used++] = lambda;
            } catch (IllegalArgumentException singular) {
                // A collinear cross-section (a factor constant across the
                // surviving assets — sector dummies in filtered universes
                // do this) prices nothing THAT period. Skip it like a
                // thin period; aborting 59 good periods for one bad one
                // would be the wrong trade.
            }
        }
        if (used < 12) {
            throw new IllegalArgumentException("only " + used
                    + " usable cross-sections — premia need a time series");
        }
        double[] premia = new double[factors];
        double[] tStats = new double[factors];
        double interceptMean = 0;
        double interceptT = 0;
        for (int k = 0; k <= factors; k++) {
            double[] series = new double[used];
            for (int t = 0; t < used; t++) {
                series[t] = lambdas[t][k];
            }
            double mean = MathUtils.mean(series);
            double se = MathUtils.stdDev(series) / Math.sqrt(used);
            double tStat = se > 0 ? mean / se : Math.signum(mean) * Double.POSITIVE_INFINITY;
            if (k == 0) {
                interceptMean = mean;
                interceptT = tStat;
            } else {
                premia[k - 1] = mean;
                tStats[k - 1] = tStat;
            }
        }
        return new Result(premia, tStats, interceptMean, interceptT, used);
    }

    private static boolean hasNaN(double[] a) {
        for (double x : a) {
            if (Double.isNaN(x)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasInfinity(double[] a) {
        for (double x : a) {
            if (Double.isInfinite(x)) {
                return true;
            }
        }
        return false;
    }
}
