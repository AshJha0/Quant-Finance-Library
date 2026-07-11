package com.quantfinlib.pricing;

import com.quantfinlib.volatility.VolatilityIndex;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Variance swap: strike = index², notional bridge, additive-in-time MTM. */
class VarianceSwapTest {

    // A reasonably fine flat-vol chain around F (2.5-wide strikes): the
    // 1/K² replication is a rectangle rule, so recovery accuracy is a
    // direct function of strike spacing (10-wide strikes overstate a
    // 0.04 variance by ~0.007 — measured, which is itself the lesson).
    private static final double[] STRIKES = {
            70, 72.5, 75, 77.5, 80, 82.5, 85, 87.5, 90, 92.5, 95, 97.5, 100,
            102.5, 105, 107.5, 110, 112.5, 115, 117.5, 120, 122.5, 125, 127.5, 130};
    private static final double F = 100.9;
    private static final double R = 0.01;
    private static final double T = 0.25;

    // Spot chosen so the BS forward S*e^{rT} equals F (carry = 0).
    private static final double SPOT = F * Math.exp(-R * T);

    private static double[] putMids() {
        double[] p = new double[STRIKES.length];
        for (int i = 0; i < STRIKES.length; i++) {
            p[i] = Math.max(0, BlackScholes.price(
                    BlackScholes.OptionType.PUT, SPOT, STRIKES[i], R, 0, 0.2, T));
        }
        return p;
    }

    private static double[] callMids() {
        double[] c = new double[STRIKES.length];
        for (int i = 0; i < STRIKES.length; i++) {
            c[i] = Math.max(0, BlackScholes.price(
                    BlackScholes.OptionType.CALL, SPOT, STRIKES[i], R, 0, 0.2, T));
        }
        return c;
    }

    @Test
    void fairVarianceIsTheSquaredIndexAndNearFlatVolSquared() {
        double vol = VolatilityIndex.index(STRIKES, putMids(), callMids(), F, R, T);
        double kVar = VarianceSwap.fairVariance(STRIKES, putMids(), callMids(), F, R, T);
        assertEquals(vol * vol, kVar, 1e-15, "one number, two names");
        // Coarse chain, so generous tolerance — the tight recovery pin
        // lives with VolatilityIndex.
        assertEquals(0.04, kVar, 4e-3);
    }

    @Test
    void vegaToVarianceNotionalBridge() {
        // 100k vega at a 20-vol strike: 100000 / (2 * 0.20) = 250000.
        assertEquals(250_000, VarianceSwap.varianceNotional(100_000, 0.20), 1e-9);
        assertThrows(IllegalArgumentException.class,
                () -> VarianceSwap.varianceNotional(100_000, 0));
        assertThrows(IllegalArgumentException.class,
                () -> VarianceSwap.varianceNotional(Double.NaN, 0.2));
    }

    @Test
    void markToMarketBlendsRealizedAndRemainingAdditively() {
        // Halfway: 0.5*0.09 + 0.5*0.05 - 0.04 = 0.03 per unit var notional.
        assertEquals(0.03, VarianceSwap.markToMarket(0.04, 0.09, 0.05, 0.5, 1.0, 0.0), 1e-12);
        // Same with discounting at 2% for the remaining half year.
        assertEquals(0.03 * Math.exp(-0.02 * 0.5),
                VarianceSwap.markToMarket(0.04, 0.09, 0.05, 0.5, 1.0, 0.02), 1e-12);
        // At inception with the strike at fair: worth exactly zero.
        assertEquals(0.0, VarianceSwap.markToMarket(0.04, 0.0, 0.04, 0.0, 1.0, 0.03), 1e-15);
        // At expiry: the settlement payoff, undiscounted.
        assertEquals(0.0225, VarianceSwap.markToMarket(0.04, 0.0625, 0.0, 1.0, 1.0, 0.05), 1e-12);
    }

    @Test
    void markToMarketGates() {
        assertThrows(IllegalArgumentException.class,
                () -> VarianceSwap.markToMarket(0, 0.04, 0.04, 0.5, 1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> VarianceSwap.markToMarket(0.04, -0.01, 0.04, 0.5, 1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> VarianceSwap.markToMarket(0.04, 0.04, 0.04, 1.5, 1, 0)); // t > T
        assertThrows(IllegalArgumentException.class,
                () -> VarianceSwap.markToMarket(0.04, 0.04, 0.04, 0, 0, 0));   // T = 0
        assertThrows(IllegalArgumentException.class,
                () -> VarianceSwap.markToMarket(0.04, 0.04, 0.04, 0.5, 1, Double.NaN));
    }
}
