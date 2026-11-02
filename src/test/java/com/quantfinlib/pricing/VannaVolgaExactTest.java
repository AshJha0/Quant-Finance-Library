package com.quantfinlib.pricing;

import com.quantfinlib.pricing.BlackScholes.OptionType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Exact (3x3 linear solve) vanna-volga against the closed-form weights:
 * pillar reproduction, the flat-smile limit, Greek matching and agreement
 * with the Castagna-Mercurio (2007) closed form.
 */
class VannaVolgaExactTest {

    private static final double S = 1.0850;
    private static final double R = 0.045;
    private static final double Q = 0.030;
    private static final double T = 0.5;
    private static final double[] STRIKES = {1.04, 1.09, 1.14};
    private static final double[] VOLS = {0.098, 0.090, 0.094};

    private final VannaVolga vv = new VannaVolga(STRIKES, VOLS, R, Q, T);

    @Test
    void exactWeightsAreUnitVectorsAtPillarsSoPillarsRepriceExactly() {
        for (int i = 0; i < 3; i++) {
            double[] x = vv.exactWeights(S, STRIKES[i]);
            double[] unit = new double[3];
            unit[i] = 1;
            assertArrayEquals(unit, x, 1e-10);
            double market = BlackScholes.price(OptionType.CALL, S, STRIKES[i], R, Q, VOLS[i], T);
            assertEquals(market, vv.priceExact(OptionType.CALL, S, STRIKES[i]), 1e-12);
        }
    }

    @Test
    void weightsReplicateTargetVegaVannaVolga() {
        double k = 1.12;
        double atm = VOLS[1];
        double[] x = vv.exactWeights(S, k);
        double vega = 0;
        double vanna = 0;
        double volga = 0;
        for (int i = 0; i < 3; i++) {
            vega += x[i] * BlackScholes.vega(S, STRIKES[i], R, Q, atm, T);
            vanna += x[i] * HigherOrderGreeks.vanna(S, STRIKES[i], R, Q, atm, T);
            volga += x[i] * HigherOrderGreeks.volga(S, STRIKES[i], R, Q, atm, T);
        }
        assertEquals(BlackScholes.vega(S, k, R, Q, atm, T), vega, 1e-12);
        assertEquals(HigherOrderGreeks.vanna(S, k, R, Q, atm, T), vanna, 1e-11);
        assertEquals(HigherOrderGreeks.volga(S, k, R, Q, atm, T), volga, 1e-11);
    }

    @Test
    void flatSmileCollapsesToBlackScholes() {
        VannaVolga flat = new VannaVolga(STRIKES, new double[]{0.09, 0.09, 0.09}, R, Q, T);
        for (double k : new double[]{0.95, 1.05, 1.10, 1.20}) {
            assertEquals(BlackScholes.price(OptionType.PUT, S, k, R, Q, 0.09, T),
                    flat.priceExact(OptionType.PUT, S, k), 1e-15);
            assertEquals(0, flat.pillarSmileCost(S, 0), 0);
        }
    }

    @Test
    void exactSolveMatchesClosedFormWeights() {
        // Castagna and Mercurio (2007) show the closed-form log-strike weights
        // solve the same system; near ATM and across the pillar range the two
        // prices agree to rounding.
        for (double k = 1.00; k <= 1.18; k += 0.005) {
            assertEquals(vv.price(OptionType.CALL, S, k), vv.priceExact(OptionType.CALL, S, k), 1e-12, "k=" + k);
        }
    }

    @Test
    void accessorsAndValidation() {
        assertEquals(STRIKES[2], vv.strike(2));
        assertEquals(VOLS[0], vv.vol(0));
        assertEquals(R, vv.rate());
        assertEquals(Q, vv.carry());
        assertEquals(T, vv.timeYears());
        assertThrows(IllegalArgumentException.class, () -> vv.exactWeights(0, 1));
        assertThrows(IllegalArgumentException.class, () -> vv.weightsFor(S, Double.NaN, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> vv.pillarSmileCost(S, 3));
    }
}
