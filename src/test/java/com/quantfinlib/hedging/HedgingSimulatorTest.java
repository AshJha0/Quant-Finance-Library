package com.quantfinlib.hedging;

import com.quantfinlib.pricing.BlackScholes.OptionType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HedgingSimulatorTest {

    private static final double SPOT = 100, STRIKE = 100, T = 0.5, RATE = 0.02;

    @Test
    void wellSpecifiedHedgeCentersNearZero() {
        // Hedge vol == realized vol, daily rebalancing: pure discretization error.
        HedgingErrorDistribution d = new HedgingSimulator(42).simulate(
                OptionType.CALL, SPOT, STRIKE, T, RATE, 0,
                0.20, 0.20, 126, 2_000, DeltaHedger.Config.every(0));

        assertEquals(2_000, d.paths());
        assertTrue(Math.abs(d.mean()) < 0.10 * d.premium(),
                "mean " + d.mean() + " vs premium " + d.premium());
        assertTrue(d.relativeHedgeError() < 0.20, "relError=" + d.relativeHedgeError());
        // Discretization error is two-sided.
        assertTrue(d.probabilityOfLoss() > 0.2 && d.probabilityOfLoss() < 0.8);
        assertTrue(d.worst() < 0 && d.best() > 0);
        assertTrue(d.conditionalValueAtRisk(0.95) >= d.valueAtRisk(0.95));
    }

    @Test
    void sellingRichVolIsProfitableCheapVolLoses() {
        HedgingSimulator sim = new HedgingSimulator(7);
        // Sold at 25 vol, market realized only 15: hedged short option profits.
        HedgingErrorDistribution rich = sim.simulate(OptionType.CALL, SPOT, STRIKE, T, RATE, 0,
                0.25, 0.15, 126, 1_500, DeltaHedger.Config.every(0));
        // Sold at 15 vol, market realized 25: hedged short option bleeds.
        HedgingErrorDistribution cheap = sim.simulate(OptionType.CALL, SPOT, STRIKE, T, RATE, 0,
                0.15, 0.25, 126, 1_500, DeltaHedger.Config.every(0));

        assertTrue(rich.mean() > 1.0, "rich vol mean " + rich.mean());
        assertTrue(cheap.mean() < -1.0, "cheap vol mean " + cheap.mean());
        assertTrue(rich.probabilityOfLoss() < 0.30);
        assertTrue(cheap.probabilityOfLoss() > 0.70);
    }

    @Test
    void moreRebalancingShrinksTheErrorDistribution() {
        HedgingSimulator sim = new HedgingSimulator(11);
        HedgingErrorDistribution sparse = sim.simulate(OptionType.CALL, SPOT, STRIKE, T, RATE, 0,
                0.20, 0.20, 16, 1_000, DeltaHedger.Config.every(0));
        HedgingErrorDistribution dense = sim.simulate(OptionType.CALL, SPOT, STRIKE, T, RATE, 0,
                0.20, 0.20, 252, 1_000, DeltaHedger.Config.every(0));

        assertTrue(dense.stdDev() < 0.5 * sparse.stdDev(),
                "dense " + dense.stdDev() + " vs sparse " + sparse.stdDev());
        assertTrue(dense.valueAtRisk(0.95) < sparse.valueAtRisk(0.95));
        assertTrue(dense.meanRebalances() > sparse.meanRebalances());
    }

    @Test
    void transactionCostsShiftTheDistributionDown() {
        HedgingSimulator sim = new HedgingSimulator(3);
        HedgingErrorDistribution free = sim.simulate(OptionType.PUT, SPOT, STRIKE, T, RATE, 0,
                0.20, 0.20, 126, 1_000, DeltaHedger.Config.every(0));
        HedgingErrorDistribution costly = sim.simulate(OptionType.PUT, SPOT, STRIKE, T, RATE, 0,
                0.20, 0.20, 126, 1_000, DeltaHedger.Config.every(5));

        assertEquals(0, free.meanTradingCosts(), 1e-12);
        assertTrue(costly.meanTradingCosts() > 0);
        assertEquals(free.mean() - costly.meanTradingCosts(), costly.mean(), 0.02);
    }

    @Test
    void deterministicForSameSeed() {
        HedgingErrorDistribution a = new HedgingSimulator(99).simulate(
                OptionType.CALL, SPOT, STRIKE, T, RATE, 0, 0.2, 0.2, 63, 500,
                new DeltaHedger.Config(0.05, 2));
        HedgingErrorDistribution b = new HedgingSimulator(99).simulate(
                OptionType.CALL, SPOT, STRIKE, T, RATE, 0, 0.2, 0.2, 63, 500,
                new DeltaHedger.Config(0.05, 2));
        assertEquals(a.mean(), b.mean(), 0.0);
        assertEquals(a.worst(), b.worst(), 0.0);
        assertEquals(a.meanRebalances(), b.meanRebalances(), 0.0);
    }
}
