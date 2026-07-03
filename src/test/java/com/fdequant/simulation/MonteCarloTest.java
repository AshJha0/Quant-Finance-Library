package com.fdequant.simulation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MonteCarloTest {

    @Test
    void expectedValueMatchesGbmTheory() {
        // E[S_T] = S0 * exp(mu * T); one year, mu = 7%.
        SimulationResult r = new MonteCarloSimulator(42)
                .simulate(100_000, 0.07, 0.15, 252, 50_000);
        assertEquals(100_000 * Math.exp(0.07), r.expectedValue(), 100_000 * 0.01);
        // Lognormal: median < mean.
        assertTrue(r.medianValue() < r.expectedValue());
    }

    @Test
    void analyticsAreInternallyConsistent() {
        SimulationResult r = new MonteCarloSimulator(1)
                .simulate(1_000_000, 0.05, 0.20, 126, 20_000);
        assertEquals(1.0, r.probabilityOfProfit() + r.probabilityOfLoss(), 1e-12);
        assertTrue(r.valueAtRisk(0.99) >= r.valueAtRisk(0.95));
        assertTrue(r.conditionalValueAtRisk(0.95) >= r.valueAtRisk(0.95));
        assertTrue(r.bestCase() >= r.medianValue() && r.medianValue() >= r.worstCase());
        double[] ci = r.confidenceInterval(0.90);
        assertTrue(ci[0] < ci[1]);
        assertEquals(20_000, r.simulations());
    }

    @Test
    void deterministicForSameSeed() {
        SimulationResult a = new MonteCarloSimulator(9).simulate(1000, 0.08, 0.25, 60, 5_000);
        SimulationResult b = new MonteCarloSimulator(9).simulate(1000, 0.08, 0.25, 60, 5_000);
        assertEquals(a.expectedValue(), b.expectedValue(), 0.0);
        assertEquals(a.worstCase(), b.worstCase(), 0.0);
    }

    @Test
    void correlatedPortfolioSimulationRuns() {
        double[] weights = {0.6, 0.4};
        double[] mu = {0.0004, 0.0002};
        double dailyVar1 = Math.pow(0.20 / Math.sqrt(252), 2);
        double dailyVar2 = Math.pow(0.10 / Math.sqrt(252), 2);
        double cov12 = 0.5 * Math.sqrt(dailyVar1 * dailyVar2);
        double[][] cov = {{dailyVar1, cov12}, {cov12, dailyVar2}};

        SimulationResult r = new MonteCarloSimulator(3)
                .simulatePortfolio(500_000, weights, mu, cov, 252, 20_000);
        assertTrue(r.expectedValue() > 500_000);     // positive drift
        assertTrue(r.valueAtRisk(0.95) > 0);
        assertTrue(r.probabilityOfProfit() > 0.5);
    }
}
