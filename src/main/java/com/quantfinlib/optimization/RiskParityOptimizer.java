package com.quantfinlib.optimization;

import com.quantfinlib.util.MathUtils;

/**
 * Risk parity: the portfolio where every asset contributes a set share of
 * total risk ({@code w_i (Σw)_i / w'Σw = b_i}; equal shares by default).
 *
 * <p>Solved through the convex formulation of Spinu (2013, "An algorithm for
 * computing risk parity weights", SSRN 2297383):
 * {@code min_x ½x'Σx − Σ b_i ln x_i} over {@code x > 0}, whose unique
 * minimiser satisfies {@code x_i (Σx)_i = b_i}; then {@code w = x / 1'x}.
 * Damped Newton steps with a backtracking line search that keeps x positive
 * converge quadratically, typically in under 20 iterations, including on
 * nearly collinear assets where the multiplicative fixed-point update
 * oscillates. Long-only, deterministic.</p>
 */
public final class RiskParityOptimizer {

    private RiskParityOptimizer() {
    }

    /** Equal-risk-contribution weights (expected returns used only for reporting). */
    public static PortfolioOptimizer.Allocation equalRiskContribution(
            double[] expectedReturns, double[][] covariance) {
        int n = covariance.length;
        double[] budgets = new double[n];
        java.util.Arrays.fill(budgets, 1.0 / n);
        return riskBudget(expectedReturns, covariance, budgets);
    }

    /**
     * Risk-budgeting weights: asset i carries the fraction {@code budgets[i]}
     * of portfolio variance.
     *
     * @param budgets positive, summing to 1
     */
    public static PortfolioOptimizer.Allocation riskBudget(
            double[] expectedReturns, double[][] covariance, double[] budgets) {
        int n = covariance.length;
        if (expectedReturns.length != n || budgets.length != n) {
            throw new IllegalArgumentException("expectedReturns (" + expectedReturns.length
                    + ") and budgets (" + budgets.length + ") must align with covariance (" + n + ")");
        }
        double bSum = 0;
        for (int i = 0; i < n; i++) {
            if (covariance[i].length != n) {
                throw new IllegalArgumentException("covariance row " + i + " is not length " + n);
            }
            // A zero-variance asset has no risk to contribute: no risk-parity
            // solution exists.
            if (!(covariance[i][i] > 0)) {
                throw new IllegalArgumentException(
                        "asset " + i + " has non-positive variance: " + covariance[i][i]);
            }
            if (!(budgets[i] > 0)) {
                throw new IllegalArgumentException("budgets must be positive");
            }
            bSum += budgets[i];
        }
        if (Math.abs(bSum - 1) > 1e-9) {
            throw new IllegalArgumentException("budgets must sum to 1, got " + bSum);
        }
        com.quantfinlib.risk.PsdMatrices.choleskyPsd(covariance);
        // Scale Σ to unit diagonal mean so tolerances are unit-free.
        double scale = 0;
        for (int i = 0; i < n; i++) {
            scale += covariance[i][i];
        }
        scale /= n;
        double[][] s = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                s[i][j] = covariance[i][j] / scale;
            }
        }
        double[] x = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = Math.sqrt(budgets[i]) / Math.sqrt(s[i][i]);
        }
        boolean converged = false;
        for (int iter = 0; iter < 200 && !converged; iter++) {
            double[] sx = MathUtils.matVec(s, x);
            double[] grad = new double[n];
            double[][] hess = new double[n][n];
            for (int i = 0; i < n; i++) {
                grad[i] = sx[i] - budgets[i] / x[i];
                for (int j = 0; j < n; j++) {
                    hess[i][j] = s[i][j];
                }
                hess[i][i] += budgets[i] / (x[i] * x[i]);
            }
            double[] step = MathUtils.solveLinear(hess, grad);
            double decrement = MathUtils.dot(grad, step);       // Newton decrement squared
            if (decrement < 1e-24) {
                converged = true;
                break;
            }
            double f0 = objective(s, x, budgets);
            double t = 1;
            double[] trial = new double[n];
            while (true) {
                boolean positive = true;
                for (int i = 0; i < n; i++) {
                    trial[i] = x[i] - t * step[i];
                    positive &= trial[i] > 0;
                }
                if (positive && objective(s, trial, budgets) <= f0 - 0.25 * t * decrement) {
                    break;
                }
                t *= 0.5;
                if (t < 1e-20) {
                    throw new IllegalStateException("risk-parity Newton line search failed");
                }
            }
            System.arraycopy(trial, 0, x, 0, n);
        }
        if (!converged) {
            throw new IllegalStateException("risk-parity Newton iteration did not converge");
        }
        double sum = 0;
        for (double v : x) {
            sum += v;
        }
        double[] w = new double[n];
        for (int i = 0; i < n; i++) {
            w[i] = x[i] / sum;
        }
        double ret = MathUtils.dot(expectedReturns, w);
        double vol = Math.sqrt(MathUtils.quadraticForm(w, covariance));
        return new PortfolioOptimizer.Allocation(w, ret, vol, vol == 0 ? 0 : ret / vol);
    }

    private static double objective(double[][] s, double[] x, double[] b) {
        double f = 0.5 * MathUtils.quadraticForm(x, s);
        for (int i = 0; i < x.length; i++) {
            f -= b[i] * Math.log(x[i]);
        }
        return f;
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
