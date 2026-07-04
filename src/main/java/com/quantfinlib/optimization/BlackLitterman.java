package com.quantfinlib.optimization;

import com.quantfinlib.util.MathUtils;

/**
 * Black-Litterman expected returns: start from the market-implied equilibrium
 * (reverse optimization of the market portfolio) and blend in investor views
 * with explicit confidences — the standard cure for mean-variance
 * optimizers' hypersensitivity to raw return estimates.
 *
 * <p>Posterior: {@code μ = [(τΣ)⁻¹ + PᵀΩ⁻¹P]⁻¹ [(τΣ)⁻¹Π + PᵀΩ⁻¹Q]} with
 * pick matrix P (one row per view), view returns Q, and diagonal view
 * variances Ω (smaller = more confident).</p>
 */
public final class BlackLitterman {

    private BlackLitterman() {
    }

    /** Equilibrium (implied) returns from the market portfolio: {@code Π = δ Σ w_mkt}. */
    public static double[] impliedEquilibriumReturns(double riskAversion, double[][] covariance,
                                                     double[] marketWeights) {
        double[] pi = MathUtils.matVec(covariance, marketWeights);
        for (int i = 0; i < pi.length; i++) {
            pi[i] *= riskAversion;
        }
        return pi;
    }

    /**
     * Posterior expected returns blending equilibrium and views.
     *
     * @param tau         uncertainty scaling of the prior (typically 0.01–0.05)
     * @param p           pick matrix [views][assets]; empty = no views
     * @param q           expected return of each view
     * @param omegaDiag   variance (uncertainty) of each view
     */
    public static double[] posteriorReturns(double tau, double[][] covariance,
                                            double[] equilibriumReturns,
                                            double[][] p, double[] q, double[] omegaDiag) {
        int n = equilibriumReturns.length;
        int views = p.length;
        if (views == 0) {
            return equilibriumReturns.clone();
        }
        if (q.length != views || omegaDiag.length != views) {
            throw new IllegalArgumentException("q and omega must have one entry per view");
        }
        // (τΣ)⁻¹
        double[][] tauSigma = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                tauSigma[i][j] = tau * covariance[i][j];
            }
        }
        double[][] priorPrecision = MathUtils.inverse(tauSigma);

        // A = (τΣ)⁻¹ + PᵀΩ⁻¹P ;  b = (τΣ)⁻¹Π + PᵀΩ⁻¹Q
        double[][] a = new double[n][n];
        double[] b = MathUtils.matVec(priorPrecision, equilibriumReturns);
        for (int i = 0; i < n; i++) {
            System.arraycopy(priorPrecision[i], 0, a[i], 0, n);
        }
        for (int v = 0; v < views; v++) {
            if (omegaDiag[v] <= 0) {
                throw new IllegalArgumentException("view variance must be positive");
            }
            double invOmega = 1 / omegaDiag[v];
            for (int i = 0; i < n; i++) {
                b[i] += p[v][i] * invOmega * q[v];
                for (int j = 0; j < n; j++) {
                    a[i][j] += p[v][i] * invOmega * p[v][j];
                }
            }
        }
        return MathUtils.solveLinear(a, b);
    }
}
