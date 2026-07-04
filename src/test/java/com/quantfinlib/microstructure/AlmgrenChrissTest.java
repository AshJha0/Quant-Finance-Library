package com.quantfinlib.microstructure;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlmgrenChrissTest {

    // Liquidate 1M shares over 5 days, 25 intervals; sigma $0.95/sqrt(day).
    private static final AlmgrenChriss.Params BASE = new AlmgrenChriss.Params(
            1_000_000, 5, 25, 0.95, 2.5e-6, 2.5e-7, 1e-6);

    @Test
    void riskNeutralTrajectoryIsTwap() {
        AlmgrenChriss.Trajectory twap = AlmgrenChriss.twap(BASE);
        assertEquals(0, twap.kappa(), 1e-12);
        // Linear holdings, equal trades.
        for (int j = 0; j < 25; j++) {
            assertEquals(1_000_000.0 / 25, twap.trades()[j], 1e-6);
        }
        // Closed-form TWAP cost: 0.5*gamma*X^2 + etaTilde*X^2/T.
        double tau = 5.0 / 25;
        double etaTilde = 2.5e-6 - 0.5 * 2.5e-7 * tau;
        double expected = 0.5 * 2.5e-7 * 1e12 + etaTilde * 1e12 / 5;
        assertEquals(expected, twap.expectedCost(), expected * 1e-9);
    }

    @Test
    void trajectoryConservesSharesAndLiquidates() {
        AlmgrenChriss.Trajectory optimal = AlmgrenChriss.optimalTrajectory(BASE);
        assertEquals(1_000_000, optimal.holdings()[0], 1e-6);
        assertEquals(0, optimal.holdings()[25], 1e-9);
        double sum = 0;
        for (double trade : optimal.trades()) {
            assertTrue(trade > 0, "monotone liquidation");
            sum += trade;
        }
        assertEquals(1_000_000, sum, 1e-6);
    }

    @Test
    void riskAversionFrontLoadsExecution() {
        AlmgrenChriss.Trajectory optimal = AlmgrenChriss.optimalTrajectory(BASE);
        assertTrue(optimal.kappa() > 0);
        // First trade bigger than the last; holdings convex (below linear).
        assertTrue(optimal.trades()[0] > optimal.trades()[24]);
        double linearMid = 1_000_000 * (1 - 12.0 / 25);
        assertTrue(optimal.holdings()[12] < linearMid,
                "risk-averse trajectory must sit below TWAP");
    }

    @Test
    void efficientFrontierTradesCostForRisk() {
        List<AlmgrenChriss.Trajectory> frontier = AlmgrenChriss.efficientFrontier(
                BASE, new double[]{0, 1e-7, 1e-6, 1e-5});
        for (int i = 1; i < frontier.size(); i++) {
            // More risk aversion: higher expected cost, lower variance.
            assertTrue(frontier.get(i).expectedCost() >= frontier.get(i - 1).expectedCost() - 1e-9,
                    "cost must rise with urgency");
            assertTrue(frontier.get(i).costVariance() < frontier.get(i - 1).costVariance(),
                    "variance must fall with urgency");
        }
    }

    @Test
    void rejectsDegenerateInputs() {
        assertThrows(IllegalArgumentException.class, () -> new AlmgrenChriss.Params(
                0, 5, 25, 0.95, 2.5e-6, 2.5e-7, 1e-6));
        // etaTilde <= 0: permanent impact too large for the interval size.
        assertThrows(IllegalArgumentException.class, () -> AlmgrenChriss.optimalTrajectory(
                new AlmgrenChriss.Params(1_000, 5, 5, 0.95, 1e-8, 1e-6, 1e-6)));
    }
}
