package com.quantfinlib.rates;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The additive exact-accrual bond path and analytic convexity: identical to
 * the whole-period functions on whole periods (so existing results are
 * untouched), different from the rounded result off them, and consistent
 * with its own derivatives. QuantLib anchors are in {@link QuantLibGoldenTest}.
 */
class BondPricerExactTest {

    @Test
    void wholePeriodsAgreeWithTheExistingFunctions() {
        for (double years : new double[] {0.5, 1, 2.5, 10, 30}) {
            assertTrue(BondPricer.isWholePeriods(2, years));
            assertEquals(BondPricer.priceFromYield(100, 0.05, 2, years, 0.045),
                    BondPricer.priceFromYieldExact(100, 0.05, 2, years, 0.045), 1e-12);
            assertEquals(BondPricer.macaulayDuration(100, 0.05, 2, years, 0.045),
                    BondPricer.macaulayDurationExact(100, 0.05, 2, years, 0.045), 1e-12);
            assertEquals(BondPricer.modifiedDuration(100, 0.05, 2, years, 0.045),
                    BondPricer.modifiedDurationExact(100, 0.05, 2, years, 0.045), 1e-12);
            assertEquals(BondPricer.convexityAnalytic(100, 0.05, 2, years, 0.045),
                    BondPricer.convexityExact(100, 0.05, 2, years, 0.045), 1e-12);
            assertEquals(0, BondPricer.accruedInterestExact(100, 0.05, 2, years), 0);
        }
    }

    @Test
    void analyticConvexityMatchesTheNumericOne() {
        double a = BondPricer.convexityAnalytic(100, 0.05, 2, 10, 0.045);
        double n = BondPricer.convexity(100, 0.05, 2, 10, 0.045);
        assertEquals(a, n, 1e-6 * a);
    }

    @Test
    void nonIntegralMaturityIsNotRounded() {
        assertFalse(BondPricer.isWholePeriods(2, 2.3));
        double rounded = BondPricer.priceFromYield(100, 0.05, 2, 2.3, 0.045);
        assertEquals(BondPricer.priceFromYield(100, 0.05, 2, 2.5, 0.045), rounded, 0); // unchanged behaviour
        double exact = BondPricer.priceFromYieldExact(100, 0.05, 2, 2.3, 0.045);
        assertNotEquals(rounded, exact);
        // Hand computation: 5 flows at period exponents 0.6, 1.6, ..., 4.6.
        double v = 1 / 1.0225;
        double hand = 0;
        for (int k = 0; k < 5; k++) {
            hand += 2.5 * Math.pow(v, 0.6 + k);
        }
        hand += 100 * Math.pow(v, 4.6);
        assertEquals(hand, exact, 1e-12);
        assertEquals(2.5 * 0.4, BondPricer.accruedInterestExact(100, 0.05, 2, 2.3), 1e-12);
    }

    @Test
    void exactDerivativesAreConsistent() {
        double years = 7.37;
        double y = 0.041;
        double h = 1e-5;
        double p = BondPricer.priceFromYieldExact(100, 0.04, 2, years, y);
        double up = BondPricer.priceFromYieldExact(100, 0.04, 2, years, y + h);
        double dn = BondPricer.priceFromYieldExact(100, 0.04, 2, years, y - h);
        assertEquals(-(up - dn) / (2 * h) / p, BondPricer.modifiedDurationExact(100, 0.04, 2, years, y), 1e-8);
        assertEquals((up + dn - 2 * p) / (h * h) / p, BondPricer.convexityExact(100, 0.04, 2, years, y), 1e-3);
        assertEquals(y, BondPricer.yieldToMaturityExact(p, 100, 0.04, 2, years), 1e-14);
    }

    @Test
    void validation() {
        assertThrows(IllegalArgumentException.class, () -> BondPricer.priceFromYieldExact(100, 0.05, 0, 1, 0.04));
        assertThrows(IllegalArgumentException.class, () -> BondPricer.isWholePeriods(2, -1));
        assertThrows(IllegalArgumentException.class, () -> BondPricer.isWholePeriods(2, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> BondPricer.yieldToMaturityExact(-5, 100, 0.05, 2, 3));
    }
}
