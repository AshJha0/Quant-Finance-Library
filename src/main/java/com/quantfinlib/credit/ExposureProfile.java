package com.quantfinlib.credit;

import java.util.Arrays;

/**
 * Output of {@link ExposureSimulator}: per exposure date, the profile
 * statistics, and per path, the discounted exposures the wrong-way CVA in
 * {@link CvaApproximator} needs.
 *
 * <p>Definitions (Gregory, The xVA Challenge, 3rd ed., 2015, ch. 7),
 * with E(t) = max(V(t) - C(t), 0) the collateralised positive exposure,
 * N(t) = max(C(t) - V(t), 0) the negative exposure and D(0,t) the
 * simulated stochastic discount factor:</p>
 * <ul>
 *   <li>{@link #discountedExpectedExposure(int)} = E[D(0,t) E(t)], the
 *   quantity CVA integrates;</li>
 *   <li>{@link #expectedExposure(int)} = E[D(0,t) E(t)] / P(0,t), the
 *   forward-measure EE; with deterministic rates it is plain E[E(t)];</li>
 *   <li>{@link #expectedNegativeExposure(int)} likewise for N (reported
 *   as a positive number), the input to DVA;</li>
 *   <li>{@link #pfe(int, double)}: the q-quantile of the undiscounted
 *   E(t) across paths (lower empirical quantile);</li>
 *   <li>{@link #epe()}: time average of EE over (0, t_m], each date's EE
 *   weighted by the length of the bucket it closes.</li>
 * </ul>
 */
public final class ExposureProfile {

    /** Market factor used as the wrong-way driver. */
    public enum Factor {
        /** Hull-White rate Brownian; high values mean high rates. */
        RATES,
        /** FX Brownian; high values mean a strong foreign currency. */
        FX
    }

    private final double[] times;
    private final double[] p0;
    private final double[][] discPos;
    private final double[][] expo;
    private final double[][] driverRates;
    private final double[][] driverFx;
    private final double[] dee;
    private final double[] dene;
    private final double[] stdErr;

    ExposureProfile(double[] times, double[] p0, double[][] discPos, double[][] discNeg,
                    double[][] expo, double[][] driverRates, double[][] driverFx) {
        this.times = times;
        this.p0 = p0;
        this.discPos = discPos;
        this.expo = expo;
        this.driverRates = driverRates;
        this.driverFx = driverFx;
        int m = times.length;
        int n = discPos.length;
        dee = new double[m];
        dene = new double[m];
        stdErr = new double[m];
        for (int i = 0; i < m; i++) {
            double s = 0, s2 = 0, sn = 0;
            for (int p = 0; p < n; p++) {
                s += discPos[p][i];
                s2 += discPos[p][i] * discPos[p][i];
                sn += discNeg[p][i];
            }
            dee[i] = s / n;
            dene[i] = sn / n;
            stdErr[i] = Math.sqrt(Math.max(s2 / n - dee[i] * dee[i], 0) / (n - 1));
        }
    }

    /** Number of exposure dates. */
    public int timesLength() {
        return times.length;
    }

    /** Exposure date i in years. */
    public double time(int i) {
        return times[i];
    }

    /** Copy of the exposure dates. */
    public double[] timesCopy() {
        return times.clone();
    }

    /** Number of simulated paths. */
    public int paths() {
        return discPos.length;
    }

    /** E[D(0,t_i) E(t_i)]. */
    public double discountedExpectedExposure(int i) {
        return dee[i];
    }

    /** Monte Carlo standard error of {@link #discountedExpectedExposure(int)}. */
    public double discountedExpectedExposureStdError(int i) {
        return stdErr[i];
    }

    /** E[D(0,t_i) N(t_i)], positive. */
    public double discountedExpectedNegativeExposure(int i) {
        return dene[i];
    }

    /** Forward-measure EE at date i: discounted EE over P(0, t_i). */
    public double expectedExposure(int i) {
        return dee[i] / p0[i];
    }

    /** Forward-measure ENE at date i, positive. */
    public double expectedNegativeExposure(int i) {
        return dene[i] / p0[i];
    }

    /** Copy of the forward-measure EE profile. */
    public double[] expectedExposureCopy() {
        double[] out = new double[times.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = expectedExposure(i);
        }
        return out;
    }

    /**
     * Potential future exposure: the lower empirical q-quantile of E(t_i).
     *
     * @param i date index
     * @param q quantile in (0, 1), e.g. 0.95 or 0.99
     * @return PFE at date i
     */
    public double pfe(int i, double q) {
        if (!(q > 0) || !(q < 1)) {
            throw new IllegalArgumentException("q must be in (0, 1), got " + q);
        }
        double[] col = new double[expo.length];
        for (int p = 0; p < col.length; p++) {
            col[p] = expo[p][i];
        }
        Arrays.sort(col);
        return com.quantfinlib.stats.Stats.quantileType1Sorted(col, q);
    }

    /** Time-weighted average of the forward-measure EE over (0, t_m]. */
    public double epe() {
        double s = 0, prev = 0;
        for (int i = 0; i < times.length; i++) {
            s += expectedExposure(i) * (times[i] - prev);
            prev = times[i];
        }
        return s / times[times.length - 1];
    }

    /** Discounted positive exposure on one path at one date. */
    double discountedPositive(int path, int i) {
        return discPos[path][i];
    }

    /**
     * Standardised driver (standard normal) for bucket i on one path: the
     * factor Brownian level at t_i, or its increment over the margin
     * period of risk ending at t_i when the set is collateralised.
     */
    double driver(int path, int i, Factor f) {
        return f == Factor.RATES ? driverRates[path][i] : driverFx[path][i];
    }
}
