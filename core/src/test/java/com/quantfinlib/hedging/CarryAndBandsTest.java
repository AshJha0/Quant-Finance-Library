package com.quantfinlib.hedging;

import com.quantfinlib.pricing.BlackScholes.OptionType;
import com.quantfinlib.stats.SpecialFunctions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CarryAndBandsTest {

    /**
     * With hedge vol = realised vol and daily rebalancing, the mean replication
     * P&L is zero up to discretisation and Monte Carlo noise, for any carry.
     * Before the carry leg was credited to the hedge, q = 3% on a 6-month
     * call showed a mean loss near -0.9 against a premium of about 7.9.
     */
    @Test
    void meanHedgePnlIsZeroWithDividendYield() {
        for (OptionType type : OptionType.values()) {
            HedgingErrorDistribution d = new HedgingSimulator(11).simulate(type, 100, 100, 0.5,
                    0.04, 0.03, 0.2, 0.2, 252, 8000, DeltaHedger.Config.every(0));
            double se = d.stdDev() / Math.sqrt(d.paths());
            assertTrue(Math.abs(d.mean()) < 4 * se + 0.01,
                    type + " mean " + d.mean() + " se " + se);
        }
    }

    /** The carry leg on a deterministic one-step path, by hand. */
    @Test
    void carryIsPaidOnTheHeldPosition() {
        double q = 0.05;
        double[] path = {100, 100};
        DeltaHedger.HedgeReport rep = DeltaHedger.simulateShortOption(OptionType.CALL, 100,
                1.0, 0.0, q, 0.2, path, 1.0, DeltaHedger.Config.every(0));
        // Held delta at t0: e^{-qT} N(d1), d1 = (r - q + sigma^2/2) T / sigma.
        double d1 = (0 - q + 0.02) / 0.2;
        double delta = Math.exp(-q) * SpecialFunctions.normCdf(d1);
        double carryCash = delta * 100 * Math.expm1(q);
        double expected = rep.premium() - delta * 100 + carryCash + delta * 100 - rep.payoff();
        assertEquals(expected, rep.finalPnl(), 1e-9);
    }

    @Test
    void whalleyWilmottBandCarriesTheDiscountFactor() {
        double s = 100, g = 0.04, k = 0.001, lambda = 1e-3, r = 0.05, t = 2;
        double expected = Math.cbrt(1.5 * Math.exp(-r * t) * k * s * g * g / lambda);
        assertEquals(expected, WhalleyWilmott.bandHalfWidth(s, g, k, lambda, r, t), 1e-15);
        assertEquals(Math.cbrt(1.5 * k * s * g * g / lambda),
                WhalleyWilmott.bandHalfWidth(s, g, k, lambda), 1e-15);
        assertEquals(Math.exp(-r * t / 3), WhalleyWilmott.bandHalfWidth(s, g, k, lambda, r, t)
                / WhalleyWilmott.bandHalfWidth(s, g, k, lambda), 1e-14);
    }
}
