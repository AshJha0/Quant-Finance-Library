package com.quantfinlib.optimization;

import com.quantfinlib.util.MathUtils;

/**
 * Risk parity: the portfolio where every asset contributes <i>equally</i> to
 * total risk ({@code w_i (Σw)_i} equal across assets). Solved by the standard
 * multiplicative fixed-point iteration on marginal risk contributions —
 * deterministic and long-only.
 */
public final class RiskParityOptimizer {

    private RiskParityOptimizer() {
    }

    /** Equal-risk-contribution weights (expected returns used only for reporting). */
    public static PortfolioOptimizer.Allocation equalRiskContribution(
            double[] expectedReturns, double[][] covariance) {
        int n = covariance.length;
        if (expectedReturns.length != n) {
            throw new IllegalArgumentException("expectedReturns (" + expectedReturns.length
                    + ") must align with covariance (" + n + ")");
        }
        for (int i = 0; i < n; i++) {
            if (covariance[i].length != n) {
                throw new IllegalArgumentException("covariance row " + i + " is not length " + n);
            }
            // A zero-variance asset has no risk to contribute: the ERC
            // fixed point does not exist and the multiplicative update
            // thrashes on it. Refuse rather than spin.
            if (!(covariance[i][i] > 0)) {
                throw new IllegalArgumentException(
                        "asset " + i + " has non-positive variance: " + covariance[i][i]);
            }
        }
        double[] w = new double[n];
        java.util.Arrays.fill(w, 1.0 / n);

        for (int iter = 0; iter < 10_000; iter++) {
            double[] marginal = MathUtils.matVec(covariance, w);
            double portVar = MathUtils.dot(w, marginal);
            double target = portVar / n;
            double maxDeviation = 0;
            double sum = 0;
            for (int i = 0; i < n; i++) {
                double rc = w[i] * marginal[i];
                maxDeviation = Math.max(maxDeviation, Math.abs(rc - target) / portVar);
                // Multiplicative update pulls each contribution toward the target.
                w[i] *= Math.pow(target / Math.max(rc, 1e-16), 0.5);
                sum += w[i];
            }
            for (int i = 0; i < n; i++) {
                w[i] /= sum;
            }
            if (maxDeviation < 1e-10) {
                break;
            }
        }
        double ret = MathUtils.dot(expectedReturns, w);
        double vol = Math.sqrt(MathUtils.quadraticForm(w, covariance));
        return new PortfolioOptimizer.Allocation(w, ret, vol, vol == 0 ? 0 : ret / vol);
    }

    /** Each asset's fractional contribution to portfolio variance under weights {@code w}. */
    public static double[] riskContributions(double[] w, double[][] covariance) {
        double[] marginal = MathUtils.matVec(covariance, w);
        double portVar = MathUtils.dot(w, marginal);
        double[] rc = new double[w.length];
        for (int i = 0; i < w.length; i++) {
            rc[i] = portVar == 0 ? 0 : w[i] * marginal[i] / portVar;
        }
        return rc;
    }
}
