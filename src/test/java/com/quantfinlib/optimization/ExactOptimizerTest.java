package com.quantfinlib.optimization;

import com.quantfinlib.util.MathUtils;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Optimisers against closed-form solutions and KKT conditions. */
class ExactOptimizerTest {

    private static final double[] MU = {0.06, 0.08, 0.05, 0.11};
    private static final double[][] COV = {
            {0.04, 0.01, 0.00, 0.01},
            {0.01, 0.09, 0.02, 0.02},
            {0.00, 0.02, 0.02, 0.00},
            {0.01, 0.02, 0.00, 0.16}};

    private static double[] normalisedSolve(double[][] cov, double[] rhs) {
        double[] x = MathUtils.solveLinear(cov, rhs);
        double s = 0;
        for (double v : x) {
            s += v;
        }
        for (int i = 0; i < x.length; i++) {
            x[i] /= s;
        }
        return x;
    }

    @Test
    void minVarianceEqualsTheAnalyticSolution() {
        // Unconstrained GMV: w = Σ⁻¹1 / 1'Σ⁻¹1 (all positive here, so long-only binds nothing).
        double[][] cov = {
                {0.04, 0.006, 0.002, 0.004},
                {0.006, 0.09, 0.01, 0.01},
                {0.002, 0.01, 0.02, 0.0},
                {0.004, 0.01, 0.0, 0.16}};
        double[] expected = normalisedSolve(cov, new double[]{1, 1, 1, 1});
        for (double w : expected) {
            assertTrue(w > 0);
        }
        double[] w = new PortfolioOptimizer(MU, cov).minVolatility().weights();
        for (int i = 0; i < 4; i++) {
            assertEquals(expected[i], w[i], 1e-8);
        }
        // The two-asset textbook case pinned exactly: 0.8 / 0.2.
        double[] two = new PortfolioOptimizer(new double[]{0.05, 0.1},
                new double[][]{{0.01, 0}, {0, 0.04}}).minVolatility().weights();
        assertEquals(0.8, two[0], 1e-12);
    }

    @Test
    void maxSharpeEqualsTheAnalyticTangency() {
        double rf = 0.02;
        double[] ex = new double[4];
        for (int i = 0; i < 4; i++) {
            ex[i] = MU[i] - rf;
        }
        double[] expected = normalisedSolve(COV, ex);
        for (double w : expected) {
            assertTrue(w > 0);
        }
        double[] w = new PortfolioOptimizer(MU, COV).maxSharpe(rf).weights();
        for (int i = 0; i < 4; i++) {
            assertEquals(expected[i], w[i], 1e-8);
        }
        assertThrows(IllegalArgumentException.class,
                () -> new PortfolioOptimizer(MU, COV).maxSharpe(0.2));
    }

    @Test
    void longOnlyTangencyDropsANegativeAsset() {
        // Asset 2 has a high variance and low return: its unconstrained
        // tangency weight is negative, so long-only sets it to zero and the
        // other two form the two-asset tangency.
        double[] mu = {0.08, 0.09, 0.03};
        double[][] cov = {{0.04, 0.01, 0.03}, {0.01, 0.05, 0.03}, {0.03, 0.03, 0.09}};
        double rf = 0.02;
        double[] w = new PortfolioOptimizer(mu, cov).maxSharpe(rf).weights();
        assertEquals(0, w[2], 1e-12);
        double[] two = normalisedSolve(new double[][]{{0.04, 0.01}, {0.01, 0.05}},
                new double[]{0.06, 0.07});
        assertEquals(two[0], w[0], 1e-8);
        assertEquals(two[1], w[1], 1e-8);
    }

    @Test
    void frontierPointsHitTheirTargetExactlyAndMatchTheTwoFundSolution() {
        List<PortfolioOptimizer.Allocation> f = new PortfolioOptimizer(MU, COV).efficientFrontier(9);
        double lo = 0.05;
        double hi = 0.11;
        for (int p = 0; p < 9; p++) {
            assertEquals(lo + (hi - lo) * p / 8, f.get(p).expectedReturn(), 1e-10);
        }
        // Interior target with all weights positive: closed form
        // w = Σ⁻¹(a·1 + b·μ), with a, b from the two constraints.
        double target = 0.08;
        double[] s1 = MathUtils.solveLinear(COV, new double[]{1, 1, 1, 1});
        double[] sm = MathUtils.solveLinear(COV, MU);
        double A = sum(s1), B = MathUtils.dot(MU, s1), C = MathUtils.dot(MU, sm);
        double det = A * C - B * B;
        double a = (C - B * target) / det;
        double b = (A * target - B) / det;
        double[] expected = new double[4];
        for (int i = 0; i < 4; i++) {
            expected[i] = a * s1[i] + b * sm[i];
            assertTrue(expected[i] > 0);
        }
        PortfolioOptimizer.Allocation mid = f.get(4);
        assertEquals(target, mid.expectedReturn(), 1e-12);
        for (int i = 0; i < 4; i++) {
            assertEquals(expected[i], mid.weights()[i], 1e-8);
        }
    }

    private static double sum(double[] v) {
        double s = 0;
        for (double x : v) {
            s += x;
        }
        return s;
    }

    @Test
    void boundedMinVarianceSatisfiesKkt() {
        double[] min = {0.1, 0.1, 0.1, 0.1};
        double[] max = {0.35, 0.35, 0.35, 0.35};
        double[] w = new ConstrainedPortfolioOptimizer(MU, COV).withBounds(min, max)
                .minVolatility().weights();
        assertEquals(1, sum(w), 1e-12);
        double[] grad = MathUtils.matVec(COV, w);
        // Free assets share one marginal variance ν; capped assets have
        // gradient <= ν, floored assets >= ν.
        double nu = Double.NaN;
        for (int i = 0; i < 4; i++) {
            if (w[i] > min[i] + 1e-9 && w[i] < max[i] - 1e-9) {
                if (Double.isNaN(nu)) {
                    nu = grad[i];
                } else {
                    assertEquals(nu, grad[i], 1e-10);
                }
            }
        }
        assertTrue(!Double.isNaN(nu));
        for (int i = 0; i < 4; i++) {
            if (w[i] >= max[i] - 1e-9) {
                assertTrue(grad[i] <= nu + 1e-10);
            }
            if (w[i] <= min[i] + 1e-9) {
                assertTrue(grad[i] >= nu - 1e-10);
            }
        }
        // The cap binds on asset 2, the low-variance asset.
        assertEquals(0.35, w[2], 1e-12);
    }

    @Test
    void boundedTangencyEqualsUnboundedWhenBoundsAreSlack() {
        double[] wFree = new PortfolioOptimizer(MU, COV).maxSharpe(0.02).weights();
        double[] wBox = new ConstrainedPortfolioOptimizer(MU, COV)
                .withBounds(new double[4], new double[]{0.9, 0.9, 0.9, 0.9})
                .maxSharpe(0.02).weights();
        for (int i = 0; i < 4; i++) {
            assertEquals(wFree[i], wBox[i], 1e-8);
        }
        // A punitive turnover penalty keeps the book exactly where it is.
        double[] current = {0.25, 0.25, 0.25, 0.25};
        double[] sticky = new ConstrainedPortfolioOptimizer(MU, COV)
                .withTurnoverPenalty(current, 0.5).maxSharpe(0.02).weights();
        for (int i = 0; i < 4; i++) {
            assertEquals(0.25, sticky[i], 1e-9);
        }
    }

    // ---- Risk parity ------------------------------------------------------

    @Test
    void riskParityConvergesOnNearlyCollinearAssets() {
        double rho = 0.9999;
        double[][] cov = {{0.04, rho * 0.04, 0.0}, {rho * 0.04, 0.04, 0.0}, {0.0, 0.0, 0.01}};
        PortfolioOptimizer.Allocation a =
                RiskParityOptimizer.equalRiskContribution(new double[3], cov);
        double[] rc = RiskParityOptimizer.riskContributions(a.weights(), cov);
        for (double c : rc) {
            assertEquals(1.0 / 3, c, 1e-10);
        }
        // Budgets other than equal.
        double[] b = {0.5, 0.3, 0.2};
        double[] w = RiskParityOptimizer.riskBudget(MU, COV, b.length == 3
                ? new double[]{0.4, 0.3, 0.2, 0.1} : b).weights();
        double[] rc2 = RiskParityOptimizer.riskContributions(w, COV);
        assertEquals(0.4, rc2[0], 1e-10);
        assertEquals(0.1, rc2[3], 1e-10);
    }

    // ---- Black-Litterman ----------------------------------------------------

    @Test
    void blackLittermanOneAssetClosedForm() {
        double sigma2 = 0.04, tau = 0.05, pi = 0.06, q = 0.10, omega = 0.002;
        BlackLitterman.Posterior post = BlackLitterman.posterior(tau, new double[][]{{sigma2}},
                new double[]{pi}, new double[][]{{1}}, new double[]{q}, new double[]{omega});
        double prec = 1 / (tau * sigma2) + 1 / omega;
        assertEquals((pi / (tau * sigma2) + q / omega) / prec, post.mean()[0], 1e-14);
        assertEquals(1 / prec, post.meanCovariance()[0][0], 1e-15);
        assertEquals(sigma2 + 1 / prec, post.returnCovariance()[0][0], 1e-15);
    }

    @Test
    void blackLittermanMatchesThePrecisionForm() {
        double tau = 0.05;
        double[] pi = BlackLitterman.impliedEquilibriumReturns(2.5, COV, new double[]{0.3, 0.3, 0.2, 0.2});
        double[][] p = {{1, 0, 0, 0}, {0, 1, -1, 0}};
        double[] q = {0.12, 0.03};
        double[] omega = BlackLitterman.heLittermanOmega(tau, COV, p);
        BlackLitterman.Posterior post = BlackLitterman.posterior(tau, COV, pi, p, q, omega);
        double[][] ts = new double[4][4];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                ts[i][j] = tau * COV[i][j];
            }
        }
        double[][] prec = MathUtils.inverse(ts);
        double[] rhs = MathUtils.matVec(prec, pi);
        for (int v = 0; v < 2; v++) {
            for (int i = 0; i < 4; i++) {
                rhs[i] += p[v][i] * q[v] / omega[v];
                for (int j = 0; j < 4; j++) {
                    prec[i][j] += p[v][i] * p[v][j] / omega[v];
                }
            }
        }
        double[][] m = MathUtils.inverse(prec);
        double[] mean = MathUtils.matVec(m, rhs);
        for (int i = 0; i < 4; i++) {
            assertEquals(mean[i], post.mean()[i], 1e-12);
            for (int j = 0; j < 4; j++) {
                assertEquals(m[i][j], post.meanCovariance()[i][j], 1e-14);
            }
        }
        assertThrows(IllegalArgumentException.class, () -> BlackLitterman.posterior(tau, COV, pi,
                new double[][]{{1, 0, 0}}, new double[]{0.1}, new double[]{0.01}));
    }
}
