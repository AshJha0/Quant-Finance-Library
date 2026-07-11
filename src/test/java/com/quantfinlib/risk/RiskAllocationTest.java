package com.quantfinlib.risk;

import com.quantfinlib.util.MathUtils;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Component VaR (Euler allocation) and Ledoit-Wolf shrinkage — the two
 * new portfolio-risk estimators, pinned on hand-derivable identities.
 */
class RiskAllocationTest {

    // --------------------------------------------------------------- ComponentVar

    @Test
    void componentsSumExactlyToPortfolioVarOnHandCase() {
        // w = {100, 200}, Sigma = [[0.04, 0.01], [0.01, 0.09]]:
        // Sigma w = {6, 19}, w' Sigma w = 4400, sigma = sqrt(4400).
        double[] w = {100, 200};
        double[][] cov = {{0.04, 0.01}, {0.01, 0.09}};
        ComponentVar.Allocation a = ComponentVar.allocate(w, cov, 0.99);

        double sigma = Math.sqrt(4400);
        double z = MathUtils.normInv(0.99);
        assertEquals(z * sigma, a.portfolioVar(), 1e-9);
        assertEquals(100 * z * 6 / sigma, a.components()[0], 1e-9);
        assertEquals(200 * z * 19 / sigma, a.components()[1], 1e-9);
        // The Euler identity: components sum EXACTLY to the total.
        assertEquals(a.portfolioVar(), a.components()[0] + a.components()[1], 1e-9);
        assertEquals(z * 6 / sigma, a.marginals()[0], 1e-12);
    }

    @Test
    void uncorrelatedEqualPositionsSplitEvenly() {
        double[] w = {1, 1};
        double[][] cov = {{0.01, 0}, {0, 0.01}};
        ComponentVar.Allocation a = ComponentVar.allocate(w, cov, 0.95);
        assertEquals(a.components()[0], a.components()[1], 1e-12);
        assertEquals(a.portfolioVar() / 2, a.components()[0], 1e-12);
    }

    @Test
    void hedgeHasNegativeComponentAndClosingItRaisesVar() {
        // Perfectly correlated long 100 / short 50: the short is a hedge.
        double[] w = {100, -50};
        double[][] cov = {{0.04, 0.04}, {0.04, 0.04}};
        ComponentVar.Allocation a = ComponentVar.allocate(w, cov, 0.99);
        assertTrue(a.components()[1] < 0, "a hedge owns NEGATIVE risk");
        assertEquals(a.portfolioVar(), a.components()[0] + a.components()[1], 1e-9);

        // Incremental VaR of the hedge: closing it moves sigma 10 -> 20,
        // so the "risk removed" is negative.
        double incr = ComponentVar.incremental(w, cov, 0.99, 1);
        double z = MathUtils.normInv(0.99);
        assertEquals(z * 10 - z * 20, incr, 1e-9);
        assertTrue(incr < 0);
    }

    @Test
    void componentVarGates() {
        double[][] cov = {{0.04, 0.01}, {0.01, 0.09}};
        assertThrows(IllegalArgumentException.class,
                () -> ComponentVar.allocate(new double[]{1}, cov, 0.99));
        assertThrows(IllegalArgumentException.class,
                () -> ComponentVar.allocate(new double[]{1, 1}, cov, 0.5));
        assertThrows(IllegalArgumentException.class,
                () -> ComponentVar.allocate(new double[]{1, 1}, cov, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> ComponentVar.allocate(new double[]{0, 0}, cov, 0.99)); // flat book
        assertThrows(IllegalArgumentException.class,
                () -> ComponentVar.allocate(new double[]{1, Double.NaN}, cov, 0.99));
        assertThrows(IllegalArgumentException.class,
                () -> ComponentVar.incremental(new double[]{1, 1}, cov, 0.99, 2));
    }

    // -------------------------------------------------------- CovarianceShrinkage

    private static double[][] correlatedReturns(int t, long seed) {
        // Two correlated + one independent series, deterministic.
        Random rnd = new Random(seed);
        double[][] r = new double[t][3];
        for (int i = 0; i < t; i++) {
            double common = rnd.nextGaussian();
            r[i][0] = 0.01 * common;
            r[i][1] = 0.01 * (0.8 * common + 0.6 * rnd.nextGaussian());
            r[i][2] = 0.02 * rnd.nextGaussian();
        }
        return r;
    }

    @Test
    void shrinkagePreservesAverageVarianceExactly() {
        // tr(Sigma*) = delta·n·mu + (1-delta)·tr(S) = n·mu — an identity,
        // so the average variance survives shrinkage to 1e-12.
        CovarianceShrinkage.Result res =
                CovarianceShrinkage.ledoitWolf(correlatedReturns(60, 7));
        double[][] m = res.matrix();
        double trace = m[0][0] + m[1][1] + m[2][2];
        assertEquals(res.target(), trace / 3, 1e-12);
        assertTrue(res.intensity() >= 0 && res.intensity() <= 1,
                "delta=" + res.intensity());
        for (int a = 0; a < 3; a++) {
            for (int b = 0; b < 3; b++) {
                assertEquals(m[a][b], m[b][a], 0.0, "must stay symmetric");
            }
        }
    }

    @Test
    void moreDataMeansLessShrinkage() {
        double d20 = CovarianceShrinkage.ledoitWolf(correlatedReturns(20, 42)).intensity();
        double d2000 = CovarianceShrinkage.ledoitWolf(correlatedReturns(2000, 42)).intensity();
        assertTrue(d2000 < d20,
                "T=2000 must trust the sample more: " + d2000 + " vs " + d20);
        assertTrue(d20 > 0.05, "20 observations of 3 assets is noisy: " + d20);
    }

    @Test
    void shrinkagePullsOffDiagonalsTowardZero() {
        CovarianceShrinkage.Result res =
                CovarianceShrinkage.ledoitWolf(correlatedReturns(40, 11));
        // The target has zero off-diagonals, so |shrunk| <= |sample| there.
        // Recover the sample entry from the convex combination.
        double delta = res.intensity();
        double shrunk = res.matrix()[0][1];
        double sample = shrunk / (1 - delta); // target contribution is 0
        assertTrue(Math.abs(shrunk) < Math.abs(sample),
                "off-diagonal must move toward 0: " + shrunk + " vs sample " + sample);
    }

    @Test
    void shrinkageGates() {
        assertThrows(IllegalArgumentException.class,
                () -> CovarianceShrinkage.ledoitWolf(new double[1][2]));
        double[][] ragged = {new double[]{0.1, 0.2}, new double[]{0.1}};
        assertThrows(IllegalArgumentException.class,
                () -> CovarianceShrinkage.ledoitWolf(ragged));
        double[][] nan = new double[5][2];
        nan[2][1] = Double.NaN;
        assertThrows(IllegalArgumentException.class,
                () -> CovarianceShrinkage.ledoitWolf(nan));
    }
}
