package com.quantfinlib.credit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.quantfinlib.rates.YieldCurve;
import org.junit.jupiter.api.Test;

/**
 * Exact checks on CreditSpreads. Expected prices are computed in the test
 * by summing continuously discounted cash flows (independent computation).
 */
class CreditSpreadsMutationTest {

    private static final YieldCurve CURVE =
            YieldCurve.ofZeroRates(new double[] {0.5, 3}, new double[] {0.02, 0.03});

    private static double byHand(double z) {
        // Semi-annual 6% coupon, 2 years, face 100.
        double pv = 0;
        for (int i = 1; i <= 4; i++) {
            double t = i / 2.0;
            double cf = 3 + (i == 4 ? 100 : 0);
            pv += cf * Math.exp(-(CURVE.zeroRate(t) + z) * t);
        }
        return pv;
    }

    @Test
    void priceWithZSpreadMatchesHandSum() {
        assertEquals(byHand(0.015), CreditSpreads.priceWithZSpread(100, 0.06, 2, 2, CURVE, 0.015), 1e-12);
        assertEquals(byHand(-0.01), CreditSpreads.priceWithZSpread(100, 0.06, 2, 2, CURVE, -0.01), 1e-12);
        assertThrows(IllegalArgumentException.class,
                () -> CreditSpreads.priceWithZSpread(100, 0.06, 2, 2, CURVE, Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> CreditSpreads.priceWithZSpread(100, 0.06, 2, 2, CURVE, Double.POSITIVE_INFINITY));
    }

    @Test
    void zSpreadInvertsPrice() {
        for (double z : new double[] {-0.2, 0, 0.0123, 0.5, 3.0}) {
            assertEquals(z, CreditSpreads.zSpread(byHand(z), 100, 0.06, 2, 2, CURVE), 1e-12);
        }
        // Zero coupon, annual.
        double p = 100 * Math.exp(-(0.03 + 0.02) * 3);
        assertEquals(0.02, CreditSpreads.zSpread(p, 100, 0, 1, 3, CURVE), 1e-12);
        // Bracket edges are accepted.
        assertEquals(-0.5, CreditSpreads.zSpread(byHand(-0.5), 100, 0.06, 2, 2, CURVE), 1e-12);
        assertEquals(5.0, CreditSpreads.zSpread(byHand(5.0), 100, 0.06, 2, 2, CURVE), 1e-12);
    }

    @Test
    void zSpreadRejectsBadInputs() {
        double p = byHand(0.01);
        assertThrows(IllegalArgumentException.class, () -> CreditSpreads.zSpread(0, 100, 0.06, 2, 2, CURVE));
        assertThrows(IllegalArgumentException.class,
                () -> CreditSpreads.zSpread(Double.POSITIVE_INFINITY, 100, 0.06, 2, 2, CURVE));
        assertThrows(IllegalArgumentException.class, () -> CreditSpreads.zSpread(p, 0, 0.06, 2, 2, CURVE));
        assertThrows(IllegalArgumentException.class,
                () -> CreditSpreads.zSpread(p, Double.POSITIVE_INFINITY, 0.06, 2, 2, CURVE));
        assertThrows(IllegalArgumentException.class, () -> CreditSpreads.zSpread(p, 100, -0.01, 2, 2, CURVE));
        assertThrows(IllegalArgumentException.class,
                () -> CreditSpreads.zSpread(p, 100, Double.POSITIVE_INFINITY, 2, 2, CURVE));
        assertThrows(IllegalArgumentException.class, () -> CreditSpreads.zSpread(p, 100, 0.06, 0, 2, CURVE));
        assertThrows(IllegalArgumentException.class, () -> CreditSpreads.zSpread(p, 100, 0.06, 2, 0, CURVE));
        assertThrows(IllegalArgumentException.class,
                () -> CreditSpreads.zSpread(p, 100, 0.06, 2, Double.POSITIVE_INFINITY, CURVE));
        assertThrows(IllegalArgumentException.class,
                () -> CreditSpreads.zSpread(byHand(-0.5) * 1.0001, 100, 0.06, 2, 2, CURVE));
        assertThrows(IllegalArgumentException.class,
                () -> CreditSpreads.zSpread(byHand(5.0) * 0.9999, 100, 0.06, 2, 2, CURVE));
    }
}
