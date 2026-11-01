package com.quantfinlib.optimization;

import com.quantfinlib.util.MathUtils;
import com.quantfinlib.util.ValueArrays;

/**
 * Black-Litterman expected returns: start from the market-implied equilibrium
 * (reverse optimization of the market portfolio) and blend in investor views
 * with explicit confidences — the standard cure for mean-variance
 * optimizers' hypersensitivity to raw return estimates (Black and Litterman
 * 1992; He and Litterman 1999).
 *
 * <p>With pick matrix P (k views × n assets), view returns Q, view
 * covariance Ω = diag(ω) and prior Π ~ N(μ, τΣ):</p>
 * <pre>  μ_BL = Π + τΣP'(PτΣP' + Ω)⁻¹(Q − PΠ)
 *  M    = [(τΣ)⁻¹ + P'Ω⁻¹P]⁻¹ = τΣ − τΣP'(PτΣP' + Ω)⁻¹PτΣ
 *  Σ_BL = Σ + M</pre>
 * <p>M is the posterior covariance of the mean estimate and Σ_BL the
 * covariance of next-period returns to hand to an optimiser (He and
 * Litterman 1999, eq. 13). The update is computed in the k×k view space,
 * so Σ need not be invertible. {@link #heLittermanOmega} gives the usual
 * default ω_i = (PτΣP')_ii, under which each view counts as much as the
 * prior.</p>
 */
public final class BlackLitterman {

    /** Posterior mean, posterior covariance of the mean M, and Σ + M. */
    public static final class Posterior {
        private final double[] mean;
        private final double[][] meanCovariance;
        private final double[][] returnCovariance;

        /**
         * Creates the value; array and collection arguments are copied.
         */
        public Posterior(double[] mean, double[][] meanCovariance, double[][] returnCovariance) {
            this.mean = mean == null ? null : mean.clone();
            this.meanCovariance = ValueArrays.copy(meanCovariance);
            this.returnCovariance = ValueArrays.copy(returnCovariance);
        }

        /** Element {@code i} of {@code mean}. */
        public double mean(int i) {
            return mean[i];
        }

        /** Length of {@code mean}. */
        public int meanLength() {
            return mean.length;
        }

        /** A fresh copy of {@code mean}. */
        public double[] meanCopy() {
            return mean == null ? null : mean.clone();
        }

        /** Element {@code [i][j]} of {@code meanCovariance}. */
        public double meanCovariance(int i, int j) {
            return meanCovariance[i][j];
        }

        /** Number of rows of {@code meanCovariance}. */
        public int meanCovarianceRows() {
            return meanCovariance.length;
        }

        /** A deep copy of {@code meanCovariance}. */
        public double[][] meanCovarianceCopy() {
            return ValueArrays.copy(meanCovariance);
        }

        /** Element {@code [i][j]} of {@code returnCovariance}. */
        public double returnCovariance(int i, int j) {
            return returnCovariance[i][j];
        }

        /** Number of rows of {@code returnCovariance}. */
        public int returnCovarianceRows() {
            return returnCovariance.length;
        }

        /** A deep copy of {@code returnCovariance}. */
        public double[][] returnCovarianceCopy() {
            return ValueArrays.copy(returnCovariance);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Posterior o)) {
                return false;
            }
            return java.util.Arrays.equals(mean, o.mean)
                    && java.util.Arrays.deepEquals(meanCovariance, o.meanCovariance)
                    && java.util.Arrays.deepEquals(returnCovariance, o.returnCovariance);
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Arrays.hashCode(mean);
            h = 31 * h + java.util.Arrays.deepHashCode(meanCovariance);
            h = 31 * h + java.util.Arrays.deepHashCode(returnCovariance);
            return h;
        }

        @Override
        public String toString() {
            return "Posterior[" + "mean=" + java.util.Arrays.toString(mean) + ", " + "meanCovariance=" + java.util.Arrays.deepToString(meanCovariance) + ", " + "returnCovariance=" + java.util.Arrays.deepToString(returnCovariance) + "]";
        }
    }

    private BlackLitterman() {
    }

    /** Equilibrium (implied) returns from the market portfolio: {@code Π = δ Σ w_mkt}. */
    public static double[] impliedEquilibriumReturns(double riskAversion, double[][] covariance,
                                                     double[] marketWeights) {
        int n = checkCovariance(covariance);
        if (marketWeights.length != n) {
            throw new IllegalArgumentException("market weights must have one entry per asset");
        }
        double[] pi = MathUtils.matVec(covariance, marketWeights);
        for (int i = 0; i < pi.length; i++) {
            pi[i] *= riskAversion;
        }
        return pi;
    }

    /**
     * Posterior expected returns blending equilibrium and views.
     *
     * @param tau         uncertainty scaling of the prior (typically 0.01–0.05), &gt; 0
     * @param p           pick matrix [views][assets]; empty = no views
     * @param q           expected return of each view
     * @param omegaDiag   variance (uncertainty) of each view, &gt; 0
     */
    public static double[] posteriorReturns(double tau, double[][] covariance,
                                            double[] equilibriumReturns,
                                            double[][] p, double[] q, double[] omegaDiag) {
        return posterior(tau, covariance, equilibriumReturns, p, q, omegaDiag).meanCopy();
    }

    /** Full posterior: mean, covariance of the mean and covariance of returns. */
    public static Posterior posterior(double tau, double[][] covariance,
                                      double[] equilibriumReturns,
                                      double[][] p, double[] q, double[] omegaDiag) {
        int n = checkCovariance(covariance);
        if (equilibriumReturns.length != n) {
            throw new IllegalArgumentException("equilibrium returns must have one entry per asset");
        }
        if (!(tau > 0) || tau == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("tau must be > 0 and finite");
        }
        requireFinite(equilibriumReturns, "equilibrium returns");
        int k = p.length;
        if (q.length != k || omegaDiag.length != k) {
            throw new IllegalArgumentException("q and omega must have one entry per view");
        }
        for (int v = 0; v < k; v++) {
            if (p[v].length != n) {
                throw new IllegalArgumentException("pick row " + v + " has " + p[v].length
                        + " entries, expected " + n);
            }
            requireFinite(p[v], "pick row " + v);
            if (!(omegaDiag[v] > 0) || omegaDiag[v] == Double.POSITIVE_INFINITY) {
                throw new IllegalArgumentException("view variance must be positive and finite");
            }
        }
        requireFinite(q, "view returns");
        double[][] ts = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                ts[i][j] = tau * covariance[i][j];
            }
        }
        double[] mean = equilibriumReturns.clone();
        double[][] m = new double[n][];
        for (int i = 0; i < n; i++) {
            m[i] = ts[i].clone();
        }
        if (k > 0) {
            // tsPt = τΣP' (n×k); s = PτΣP' + Ω (k×k)
            double[][] tsPt = new double[n][k];
            for (int i = 0; i < n; i++) {
                for (int v = 0; v < k; v++) {
                    double acc = 0;
                    for (int j = 0; j < n; j++) {
                        acc += ts[i][j] * p[v][j];
                    }
                    tsPt[i][v] = acc;
                }
            }
            double[][] sMat = new double[k][k];
            for (int v = 0; v < k; v++) {
                for (int u = 0; u < k; u++) {
                    double acc = 0;
                    for (int j = 0; j < n; j++) {
                        acc += p[v][j] * tsPt[j][u];
                    }
                    sMat[v][u] = acc + (v == u ? omegaDiag[v] : 0);
                }
            }
            double[] resid = new double[k];
            for (int v = 0; v < k; v++) {
                resid[v] = q[v] - MathUtils.dot(p[v], equilibriumReturns);
            }
            double[] z = MathUtils.solveLinear(sMat, resid);
            double[][] sInv = MathUtils.inverse(sMat);
            for (int i = 0; i < n; i++) {
                for (int v = 0; v < k; v++) {
                    mean[i] += tsPt[i][v] * z[v];
                }
                for (int j = 0; j < n; j++) {
                    double acc = 0;
                    for (int v = 0; v < k; v++) {
                        for (int u = 0; u < k; u++) {
                            acc += tsPt[i][v] * sInv[v][u] * tsPt[j][u];
                        }
                    }
                    m[i][j] -= acc;
                }
            }
        }
        double[][] ret = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double sym = 0.5 * (m[i][j] + m[j][i]);
                ret[i][j] = covariance[i][j] + sym;
            }
        }
        double[][] msym = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                msym[i][j] = 0.5 * (m[i][j] + m[j][i]);
            }
        }
        return new Posterior(mean, msym, ret);
    }

    /**
     * He-Litterman default view variances {@code ω_v = (P·τΣ·P')_vv}: each
     * view is as uncertain as the prior's estimate of the same portfolio.
     */
    public static double[] heLittermanOmega(double tau, double[][] covariance, double[][] p) {
        int n = checkCovariance(covariance);
        double[] omega = new double[p.length];
        for (int v = 0; v < p.length; v++) {
            if (p[v].length != n) {
                throw new IllegalArgumentException("pick row " + v + " has wrong length");
            }
            omega[v] = tau * MathUtils.quadraticForm(p[v], covariance);
        }
        return omega;
    }

    private static int checkCovariance(double[][] covariance) {
        int n = covariance.length;
        if (n < 1) {
            throw new IllegalArgumentException("empty covariance");
        }
        for (double[] row : covariance) {
            if (row.length != n) {
                throw new IllegalArgumentException("covariance must be square");
            }
            requireFinite(row, "covariance");
        }
        return n;
    }

    private static void requireFinite(double[] a, String name) {
        for (double x : a) {
            if (!Double.isFinite(x)) {
                throw new IllegalArgumentException(name + " must be finite");
            }
        }
    }
}
