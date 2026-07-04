package com.quantfinlib.optimization;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortfolioConstructionTest {

    private static final double[] MU = {0.06, 0.08, 0.05, 0.11};
    private static final double[][] COV = {
            {0.04, 0.01, 0.00, 0.01},
            {0.01, 0.09, 0.02, 0.02},
            {0.00, 0.02, 0.02, 0.00},
            {0.01, 0.02, 0.00, 0.16}};

    // ---- Risk parity -----------------------------------------------------

    @Test
    void riskParityEqualizesContributions() {
        PortfolioOptimizer.Allocation erc =
                RiskParityOptimizer.equalRiskContribution(MU, COV);
        double[] rc = RiskParityOptimizer.riskContributions(erc.weights(), COV);
        for (double contribution : rc) {
            assertEquals(0.25, contribution, 1e-6);
        }
        double sum = 0;
        for (double w : erc.weights()) {
            assertTrue(w > 0);
            sum += w;
        }
        assertEquals(1.0, sum, 1e-9);
    }

    @Test
    void riskParityMatchesInverseVolForUncorrelatedAssets() {
        // Uncorrelated 10%/20% vol: ERC weights are proportional to 1/sigma.
        double[][] diag = {{0.01, 0}, {0, 0.04}};
        PortfolioOptimizer.Allocation erc =
                RiskParityOptimizer.equalRiskContribution(new double[]{0.05, 0.05}, diag);
        assertEquals(2.0 / 3, erc.weights()[0], 1e-6);
        assertEquals(1.0 / 3, erc.weights()[1], 1e-6);
    }

    // ---- Black-Litterman ----------------------------------------------------

    @Test
    void noViewsReturnsEquilibrium() {
        double[] marketWeights = {0.3, 0.3, 0.2, 0.2};
        double[] pi = BlackLitterman.impliedEquilibriumReturns(2.5, COV, marketWeights);
        double[] posterior = BlackLitterman.posteriorReturns(0.05, COV, pi,
                new double[0][], new double[0], new double[0]);
        for (int i = 0; i < pi.length; i++) {
            assertEquals(pi[i], posterior[i], 1e-12);
        }
        // Riskier assets carry higher implied returns under reverse optimization.
        assertTrue(pi[3] > pi[2]);
    }

    @Test
    void confidentAbsoluteViewPullsPosteriorToTheView() {
        double[] pi = BlackLitterman.impliedEquilibriumReturns(2.5, COV,
                new double[]{0.25, 0.25, 0.25, 0.25});
        // View: asset 0 returns exactly 15%, with near-certainty.
        double[][] p = {{1, 0, 0, 0}};
        double[] posterior = BlackLitterman.posteriorReturns(0.05, COV, pi,
                p, new double[]{0.15}, new double[]{1e-8});
        assertEquals(0.15, posterior[0], 1e-3);
        // Weak view barely moves the prior.
        double[] weak = BlackLitterman.posteriorReturns(0.05, COV, pi,
                p, new double[]{0.15}, new double[]{10.0});
        assertEquals(pi[0], weak[0], 0.005);
    }

    @Test
    void relativeViewMovesTheSpread() {
        double[] pi = BlackLitterman.impliedEquilibriumReturns(2.5, COV,
                new double[]{0.25, 0.25, 0.25, 0.25});
        double priorSpread = pi[1] - pi[2];
        // View: asset 1 outperforms asset 2 by 10% (more than the prior spread).
        double[][] p = {{0, 1, -1, 0}};
        double[] posterior = BlackLitterman.posteriorReturns(0.05, COV, pi,
                p, new double[]{0.10}, new double[]{1e-6});
        assertEquals(0.10, posterior[1] - posterior[2], 1e-3);
        assertTrue(posterior[1] - posterior[2] > priorSpread);
    }

    // ---- Constrained optimization -----------------------------------------------

    @Test
    void positionCapsAreRespected() {
        double[] min = {0, 0, 0, 0};
        double[] max = {0.30, 0.30, 0.30, 0.30};
        PortfolioOptimizer.Allocation capped = new ConstrainedPortfolioOptimizer(MU, COV)
                .withBounds(min, max)
                .maxSharpe(0.02);
        double sum = 0;
        for (double w : capped.weights()) {
            assertTrue(w <= 0.30 + 1e-9 && w >= -1e-9, "weight " + w);
            sum += w;
        }
        assertEquals(1.0, sum, 1e-6);
        // Cap must bind somewhere: 4 assets at <=30% forces near-full usage.
        assertTrue(capped.weights()[2] > 0.05);
    }

    @Test
    void turnoverPenaltyAnchorsToCurrentHoldings() {
        double[] current = {0.25, 0.25, 0.25, 0.25};
        PortfolioOptimizer.Allocation free = new ConstrainedPortfolioOptimizer(MU, COV)
                .maxSharpe(0.02);
        PortfolioOptimizer.Allocation sticky = new ConstrainedPortfolioOptimizer(MU, COV)
                .withTurnoverPenalty(current, 0.50)   // punitive cost
                .maxSharpe(0.02);
        double freeTurnover = 0, stickyTurnover = 0;
        for (int i = 0; i < 4; i++) {
            freeTurnover += Math.abs(free.weights()[i] - current[i]);
            stickyTurnover += Math.abs(sticky.weights()[i] - current[i]);
        }
        assertTrue(stickyTurnover < 0.25 * freeTurnover,
                "sticky " + stickyTurnover + " vs free " + freeTurnover);
    }

    @Test
    void infeasibleBoundsAreRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                new ConstrainedPortfolioOptimizer(MU, COV)
                        .withBounds(new double[]{0, 0, 0, 0}, new double[]{0.2, 0.2, 0.2, 0.2}));
    }
}
