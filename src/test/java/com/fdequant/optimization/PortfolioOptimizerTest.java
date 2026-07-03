package com.fdequant.optimization;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortfolioOptimizerTest {

    @Test
    void minVolatilityMatchesTwoAssetAnalyticSolution() {
        // Uncorrelated assets, vol 10% and 20%: w1* = s2^2 / (s1^2 + s2^2) = 0.8
        double[] mu = {0.05, 0.10};
        double[][] cov = {{0.01, 0.0}, {0.0, 0.04}};
        PortfolioOptimizer.Allocation a = new PortfolioOptimizer(mu, cov).minVolatility();

        assertEquals(0.8, a.weights()[0], 0.02);
        assertEquals(0.2, a.weights()[1], 0.02);
        assertEquals(1.0, a.weights()[0] + a.weights()[1], 1e-9);
        // Analytic min vol = sqrt(0.8^2*0.01 + 0.2^2*0.04) ≈ 0.0894
        assertEquals(0.0894, a.volatility(), 0.005);
    }

    @Test
    void maxSharpeFavorsDominantAsset() {
        // Asset B dominates: same vol, higher return.
        double[] mu = {0.04, 0.12};
        double[][] cov = {{0.0225, 0.0}, {0.0, 0.0225}};
        PortfolioOptimizer.Allocation a = new PortfolioOptimizer(mu, cov).maxSharpe(0.02);
        assertTrue(a.weights()[1] > a.weights()[0], "weights=" + java.util.Arrays.toString(a.weights()));
        assertTrue(a.sharpe() > 0);
    }

    @Test
    void weightsAlwaysOnSimplex() {
        double[] mu = {0.06, 0.08, 0.05, 0.11};
        double[][] cov = {
                {0.04, 0.01, 0.00, 0.01},
                {0.01, 0.09, 0.02, 0.02},
                {0.00, 0.02, 0.02, 0.00},
                {0.01, 0.02, 0.00, 0.16}};
        for (PortfolioOptimizer.Allocation a : List.of(
                new PortfolioOptimizer(mu, cov).maxSharpe(0.02),
                new PortfolioOptimizer(mu, cov).minVolatility())) {
            double sum = 0;
            for (double w : a.weights()) {
                assertTrue(w >= -1e-9, "negative weight " + w);
                sum += w;
            }
            assertEquals(1.0, sum, 1e-9);
        }
    }

    @Test
    void efficientFrontierVolatilityRisesWithReturn() {
        double[] mu = {0.04, 0.12};
        double[][] cov = {{0.01, 0.002}, {0.002, 0.05}};
        List<PortfolioOptimizer.Allocation> frontier =
                new PortfolioOptimizer(mu, cov).efficientFrontier(5);
        assertEquals(5, frontier.size());
        // Ends of the frontier: low-return end has lower vol than high-return end.
        assertTrue(frontier.getFirst().volatility() <= frontier.getLast().volatility() + 1e-6);
    }

    @Test
    void rebalanceDeltasSumToZero() {
        double[] deltas = PortfolioOptimizer.rebalance(
                new double[]{0.6, 0.4}, new double[]{0.5, 0.5});
        assertEquals(-0.1, deltas[0], 1e-12);
        assertEquals(0.1, deltas[1], 1e-12);
    }
}
