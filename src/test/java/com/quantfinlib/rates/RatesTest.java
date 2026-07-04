package com.quantfinlib.rates;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RatesTest {

    @Test
    void bootstrapFlatParCurveRecoversFlatZeros() {
        // Flat 5% annual par swaps -> DF_n = 1.05^-n, zero_cc = ln(1.05).
        YieldCurve curve = YieldCurve.bootstrapAnnualParSwaps(
                new int[]{1, 2, 3, 5}, new double[]{0.05, 0.05, 0.05, 0.05});
        for (int y = 1; y <= 5; y++) {
            assertEquals(Math.pow(1.05, -y), curve.discountFactor(y), 1e-10);
            assertEquals(Math.log(1.05), curve.zeroRate(y), 1e-10);
        }
    }

    @Test
    void upwardSlopingParCurveImpliesHigherForwards() {
        YieldCurve curve = YieldCurve.bootstrapAnnualParSwaps(
                new int[]{1, 2, 3}, new double[]{0.02, 0.03, 0.04});
        assertTrue(curve.zeroRate(3) > curve.zeroRate(1));
        // Forward 2y->3y must exceed the 3y zero on an upward-sloping curve.
        assertTrue(curve.forwardRate(2, 3) > curve.zeroRate(3));
        assertTrue(curve.discountFactor(3) < curve.discountFactor(2));
    }

    @Test
    void zeroRateInterpolatesAndExtrapolatesFlat() {
        YieldCurve curve = YieldCurve.ofZeroRates(new double[]{1, 3}, new double[]{0.02, 0.04});
        assertEquals(0.03, curve.zeroRate(2), 1e-12);
        assertEquals(0.02, curve.zeroRate(0.5), 1e-12);   // flat short end
        assertEquals(0.04, curve.zeroRate(10), 1e-12);    // flat long end
        assertEquals(1.0, curve.discountFactor(0), 1e-12);
    }

    @Test
    void zeroCouponBondAnalytics() {
        // 5y zero-coupon at 4% annual yield.
        double price = BondPricer.priceFromYield(100, 0, 1, 5, 0.04);
        assertEquals(100 / Math.pow(1.04, 5), price, 1e-10);
        // Zero's Macaulay duration equals its maturity.
        assertEquals(5.0, BondPricer.macaulayDuration(100, 0, 1, 5, 0.04), 1e-10);
        assertEquals(5.0 / 1.04, BondPricer.modifiedDuration(100, 0, 1, 5, 0.04), 1e-10);
    }

    @Test
    void parBondPricesAtFaceAndYtmRoundTrips() {
        // Coupon == yield -> price == face.
        assertEquals(100, BondPricer.priceFromYield(100, 0.06, 2, 10, 0.06), 1e-9);

        double price = BondPricer.priceFromYield(100, 0.05, 2, 7, 0.043);
        assertEquals(0.043, BondPricer.yieldToMaturity(price, 100, 0.05, 2, 7), 1e-8);
    }

    @Test
    void convexityAndDv01ArePositiveAndConsistent() {
        double convexity = BondPricer.convexity(100, 0.05, 2, 10, 0.05);
        assertTrue(convexity > 0);
        double dv01 = BondPricer.dv01(100, 0.05, 2, 10, 0.05);
        // First-order check: price change for 1bp ~ DV01.
        double p0 = BondPricer.priceFromYield(100, 0.05, 2, 10, 0.05);
        double p1 = BondPricer.priceFromYield(100, 0.05, 2, 10, 0.0501);
        assertEquals(p0 - p1, dv01, dv01 * 0.01);
    }

    @Test
    void curvePricingMatchesYieldPricingOnFlatCurve() {
        // Flat cc curve at z; equivalent semi-annual yield y = 2(e^{z/2}-1).
        double z = 0.05;
        YieldCurve flat = YieldCurve.ofZeroRates(new double[]{1, 30}, new double[]{z, z});
        double y = 2 * (Math.exp(z / 2) - 1);
        double fromCurve = BondPricer.priceFromCurve(100, 0.06, 2, 10, flat);
        double fromYield = BondPricer.priceFromYield(100, 0.06, 2, 10, y);
        assertEquals(fromYield, fromCurve, 1e-6);
    }
}
