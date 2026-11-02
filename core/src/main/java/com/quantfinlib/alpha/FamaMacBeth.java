package com.quantfinlib.alpha;

import com.quantfinlib.stats.Stats;

/**
 * Fama-MacBeth (1973, J. Political Economy 81(3)) cross-sectional
 * regression: what a factor exposure is worth per period in return space.
 * Two passes:
 *
 * <ol>
 *   <li>each period, regress the cross-section of forward returns on that
 *       period's exposures (with intercept), giving one premium estimate
 *       {@code λ_t} per factor;</li>
 *   <li>the premium is the time-series mean of the {@code λ_t}, with a
 *       time-series standard error. Each period contributes one observation
 *       per factor, which absorbs the cross-sectional correlation that
 *       wrecks a pooled regression's errors.</li>
 * </ol>
 *
 * <p>Standard errors are Newey-West (1987) on the {@code λ_t} series. With
 * {@code horizon}-bar forward returns sampled every bar the {@code λ_t}
 * overlap and are mechanically MA({@code horizon−1}); the lag used is
 * {@code max(horizon − 1, Andrews 1991 AR(1) plug-in)} per series. The iid
 * t-stats are kept as {@code naiveTStats} for comparison. No Shanken (1992)
 * errors-in-variables correction is applied: exposures here are observed
 * characteristics (scores), not estimated betas, so the correction does not
 * apply; pass estimated betas and it would.</p>
 *
 * <p>Each cross-section is solved by Householder QR ({@link Ols}), not the
 * normal equations. With {@code standardize = true} each exposure column is
 * winsorised at mean ± 3 standard deviations and then z-scored per period,
 * so one outlier cannot own the cross-section and premia are per unit of
 * cross-sectional standard deviation.</p>
 *
 * <p>NaN entries (asset not in that period's cross-section) are skipped
 * per period; periods with fewer assets than factors + 2, or with a
 * collinear design, are skipped and not counted. Infinite values throw.</p>
 */
public final class FamaMacBeth {

    public static final class Result {
        private final double[] premia;
        private final double[] tStats;
        private final double interceptMean;
        private final double interceptTStat;
        private final int periodsUsed;
        private final double[] naiveTStats;
        private final int[] hacLags;

        /**
         * Creates the value; array and collection arguments are copied.
         *
         * @param premia mean per-period premium per factor
         * @param tStats Newey-West t-stat per factor
         * @param interceptMean mean per-period intercept (should be near 0)
         * @param interceptTStat its Newey-West t-stat
         * @param periodsUsed cross-sections that had enough assets
         * @param naiveTStats iid t-stats per factor, for comparison
         * @param hacLags Bartlett lag used per factor
         */
        public Result(double[] premia, double[] tStats, double interceptMean, double interceptTStat,
                int periodsUsed, double[] naiveTStats, int[] hacLags) {
            this.premia = premia == null ? null : premia.clone();
            this.tStats = tStats == null ? null : tStats.clone();
            this.interceptMean = interceptMean;
            this.interceptTStat = interceptTStat;
            this.periodsUsed = periodsUsed;
            this.naiveTStats = naiveTStats == null ? null : naiveTStats.clone();
            this.hacLags = hacLags == null ? null : hacLags.clone();
        }

        /** Element {@code i} of {@code premia} (mean per-period premium per factor). */
        public double premia(int i) {
            return premia[i];
        }

        /** Length of {@code premia}. */
        public int premiaLength() {
            return premia.length;
        }

        /** A fresh copy of {@code premia}. */
        public double[] premiaCopy() {
            return premia == null ? null : premia.clone();
        }

        /** Element {@code i} of {@code tStats} (Newey-West t-stat per factor). */
        public double tStats(int i) {
            return tStats[i];
        }

        /** Length of {@code tStats}. */
        public int tStatsLength() {
            return tStats.length;
        }

        /** A fresh copy of {@code tStats}. */
        public double[] tStatsCopy() {
            return tStats == null ? null : tStats.clone();
        }

        /** {@code interceptMean} (mean per-period intercept (should be near 0)). */
        public double interceptMean() {
            return interceptMean;
        }

        /** {@code interceptTStat} (its Newey-West t-stat). */
        public double interceptTStat() {
            return interceptTStat;
        }

        /** {@code periodsUsed} (cross-sections that had enough assets). */
        public int periodsUsed() {
            return periodsUsed;
        }

        /** Element {@code i} of {@code naiveTStats} (iid t-stats per factor, for comparison). */
        public double naiveTStats(int i) {
            return naiveTStats[i];
        }

        /** Length of {@code naiveTStats}. */
        public int naiveTStatsLength() {
            return naiveTStats.length;
        }

        /** A fresh copy of {@code naiveTStats}. */
        public double[] naiveTStatsCopy() {
            return naiveTStats == null ? null : naiveTStats.clone();
        }

        /** Element {@code i} of {@code hacLags} (Bartlett lag used per factor). */
        public int hacLags(int i) {
            return hacLags[i];
        }

        /** Length of {@code hacLags}. */
        public int hacLagsLength() {
            return hacLags.length;
        }

        /** A fresh copy of {@code hacLags}. */
        public int[] hacLagsCopy() {
            return hacLags == null ? null : hacLags.clone();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Result o)) {
                return false;
            }
            return java.util.Arrays.equals(premia, o.premia)
                    && java.util.Arrays.equals(tStats, o.tStats)
                    && Double.compare(interceptMean, o.interceptMean) == 0
                    && Double.compare(interceptTStat, o.interceptTStat) == 0
                    && periodsUsed == o.periodsUsed
                    && java.util.Arrays.equals(naiveTStats, o.naiveTStats)
                    && java.util.Arrays.equals(hacLags, o.hacLags);
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Arrays.hashCode(premia);
            h = 31 * h + java.util.Arrays.hashCode(tStats);
            h = 31 * h + Double.hashCode(interceptMean);
            h = 31 * h + Double.hashCode(interceptTStat);
            h = 31 * h + Integer.hashCode(periodsUsed);
            h = 31 * h + java.util.Arrays.hashCode(naiveTStats);
            h = 31 * h + java.util.Arrays.hashCode(hacLags);
            return h;
        }

        @Override
        public String toString() {
            return "Result[" + "premia=" + java.util.Arrays.toString(premia) + ", " + "tStats=" + java.util.Arrays.toString(tStats) + ", " + "interceptMean=" + interceptMean + ", " + "interceptTStat=" + interceptTStat + ", " + "periodsUsed=" + periodsUsed + ", " + "naiveTStats=" + java.util.Arrays.toString(naiveTStats) + ", " + "hacLags=" + java.util.Arrays.toString(hacLags) + "]";
        }
    }

    private FamaMacBeth() {
    }

    /** One-bar forward returns, raw exposures. */
    public static Result fit(double[][][] exposures, double[][] forwardReturns) {
        return fit(exposures, forwardReturns, 1, false);
    }

    /**
     * Fits the Fama-MacBeth (1973) two-pass cross-sectional regression.
     *
     * @param exposures      exposures[t][asset][factor], the loadings known at t
     * @param forwardReturns forwardReturns[t][asset], realised after t
     * @param horizon        bars spanned by each forward return; consecutive
     *                       periods overlap by {@code horizon − 1} bars when
     *                       periods are one bar apart
     * @param standardize    winsorise and z-score exposures per period
     */
    public static Result fit(double[][][] exposures, double[][] forwardReturns, int horizon,
                             boolean standardize) {
        int periods = exposures.length;
        if (forwardReturns.length != periods || periods < 12) {
            throw new IllegalArgumentException("need >= 12 aligned periods, got " + periods);
        }
        if (horizon < 1) {
            throw new IllegalArgumentException("horizon must be >= 1");
        }
        int factors = exposures[0].length > 0 ? exposures[0][0].length : 0;
        if (factors < 1) {
            throw new IllegalArgumentException("need >= 1 factor");
        }
        int dim = factors + 1;                       // intercept first
        double[][] lambdas = new double[periods][];
        int used = 0;
        for (int t = 0; t < periods; t++) {
            int assets = exposures[t].length;
            if (forwardReturns[t].length != assets) {
                throw new IllegalArgumentException("period " + t + " misaligned");
            }
            double[][] rows = new double[assets][];
            double[] ys = new double[assets];
            int n = 0;
            for (int a = 0; a < assets; a++) {
                if (exposures[t][a].length != factors) {
                    throw new IllegalArgumentException("period " + t + " asset " + a
                            + " has " + exposures[t][a].length + " factors, expected "
                            + factors);
                }
                double y = forwardReturns[t][a];
                if (Double.isNaN(y) || hasNaN(exposures[t][a])) {
                    continue;
                }
                if (Double.isInfinite(y) || hasInfinity(exposures[t][a])) {
                    throw new IllegalArgumentException("infinite value at period " + t
                            + " asset " + a + "; a data error, not a missing name");
                }
                double[] row = new double[dim];
                row[0] = 1;
                System.arraycopy(exposures[t][a], 0, row, 1, factors);
                rows[n] = row;
                ys[n] = y;
                n++;
            }
            if (n < factors + 2) {
                continue;
            }
            double[][] x = java.util.Arrays.copyOf(rows, n);
            double[] y = java.util.Arrays.copyOf(ys, n);
            if (standardize && !standardizeColumns(x, factors)) {
                continue; // a constant exposure column: nothing to price this period
            }
            double[] lambda;
            try {
                lambda = Ols.fit(x, y, 0).coefficientsCopy();
            } catch (IllegalArgumentException collinear) {
                continue; // a collinear cross-section prices nothing that period
            }
            lambdas[used++] = lambda;
        }
        if (used < 12) {
            throw new IllegalArgumentException("only " + used
                    + " usable cross-sections; premia need a time series");
        }
        double[] premia = new double[factors];
        double[] tStats = new double[factors];
        double[] naive = new double[factors];
        int[] lags = new int[factors];
        double interceptMean = 0;
        double interceptT = 0;
        for (int k = 0; k <= factors; k++) {
            double[] series = new double[used];
            for (int t = 0; t < used; t++) {
                series[t] = lambdas[t][k];
            }
            double mean = Stats.mean(series);
            double se = Stats.sampleStdDev(series) / Math.sqrt(used);
            int lag = NeweyWest.lagFor(series, horizon);
            double tHac = NeweyWest.tStat(series, lag);
            if (k == 0) {
                interceptMean = mean;
                interceptT = tHac;
            } else {
                premia[k - 1] = mean;
                tStats[k - 1] = tHac;
                naive[k - 1] = se > 0 ? mean / se : Math.signum(mean) * Double.POSITIVE_INFINITY;
                lags[k - 1] = lag;
            }
        }
        return new Result(premia, tStats, interceptMean, interceptT, used, naive, lags);
    }

    /**
     * Winsorise each exposure column (1..factors) at mean ± 3σ, then
     * z-score it. False when a column has zero dispersion.
     */
    private static boolean standardizeColumns(double[][] x, int factors) {
        int n = x.length;
        for (int k = 1; k <= factors; k++) {
            for (int pass = 0; pass < 2; pass++) {
                double mean = 0;
                for (double[] r : x) {
                    mean += r[k];
                }
                mean /= n;
                double var = 0;
                for (double[] r : x) {
                    var += (r[k] - mean) * (r[k] - mean);
                }
                double sd = Math.sqrt(var / n);
                if (sd == 0) {
                    return false;
                }
                for (double[] r : x) {
                    r[k] = pass == 0
                            ? Math.max(mean - 3 * sd, Math.min(mean + 3 * sd, r[k]))
                            : (r[k] - mean) / sd;
                }
            }
        }
        return true;
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
