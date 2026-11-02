package com.quantfinlib.credit;

import com.quantfinlib.rates.YieldCurve;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Credit curve + CDS pricing pinned by the two identities that define
 * them: every input reprices exactly (bootstrap), and the credit
 * triangle spread ~ h*(1-R) holds on a flat curve.
 */
class CreditTest {

    private static YieldCurve flat3() {
        return YieldCurve.ofZeroRates(new double[]{1, 2, 3, 5, 7, 10},
                new double[]{0.03, 0.03, 0.03, 0.03, 0.03, 0.03});
    }

    @Test
    void flatSpreadsSatisfyTheCreditTriangle() {
        // 100bp flat, R = 40%: h ~ S/(1-R) = 1.667%. The quarterly
        // discretization and accrual-on-default term move it by well
        // under a basis point of hazard.
        CreditCurve curve = CreditCurve.bootstrap(new int[]{1, 3, 5},
                new double[]{0.01, 0.01, 0.01}, 0.40, flat3());
        assertEquals(0.01 / 0.60, curve.hazard(2.0), 3e-4);
        assertEquals(0.01 / 0.60, curve.hazard(4.0), 3e-4);
        // Survival is monotone from 1 and consistent with its complement.
        assertEquals(1.0, curve.survivalProbability(0), 0.0);
        assertTrue(curve.survivalProbability(1) > curve.survivalProbability(5));
        assertEquals(1 - curve.survivalProbability(3), curve.defaultProbability(3), 0.0);
    }

    @Test
    void bootstrapRepricesEveryPillarExactly() {
        int[] tenors = {1, 3, 5, 7};
        double[] spreads = {0.008, 0.012, 0.015, 0.016}; // upward credit curve
        YieldCurve discount = flat3();
        CreditCurve curve = CreditCurve.bootstrap(tenors, spreads, 0.40, discount);
        for (int i = 0; i < tenors.length; i++) {
            assertEquals(spreads[i], CdsPricer.parSpread(curve, discount, tenors[i]), 1e-10,
                    tenors[i] + "y must reprice");
        }
        // Upward spreads need upward hazards.
        assertTrue(curve.hazard(4.0) > curve.hazard(0.5));
    }

    @Test
    void upfrontIsZeroAtParAndPositiveForCheapCoupons() {
        YieldCurve discount = flat3();
        CreditCurve curve = CreditCurve.bootstrap(new int[]{5},
                new double[]{0.03}, 0.40, discount);
        double par = CdsPricer.parSpread(curve, discount, 5);
        assertEquals(0.0, CdsPricer.upfront(curve, discount, par, 5), 1e-12);
        // Standard 100bp contract coupon on a 300bp name: the buyer pays
        // points up front — roughly (300-100)bp times the risky annuity.
        double up = CdsPricer.upfront(curve, discount, 0.01, 5);
        assertTrue(up > 0);
        assertEquals((par - 0.01) * CdsPricer.riskyAnnuity(curve, discount, 5), up, 1e-12);
    }

    @Test
    void creditGatesRefuseNonsense() {
        YieldCurve d = flat3();
        assertThrows(IllegalArgumentException.class, () -> CreditCurve.bootstrap(
                new int[]{3, 1}, new double[]{0.01, 0.01}, 0.4, d));       // descending
        assertThrows(IllegalArgumentException.class, () -> CreditCurve.bootstrap(
                new int[]{1}, new double[]{-0.01}, 0.4, d));               // negative spread
        assertThrows(IllegalArgumentException.class, () -> CreditCurve.bootstrap(
                new int[]{1}, new double[]{0.01}, 1.0, d));                // recovery = 1
        assertThrows(IllegalArgumentException.class, () -> CreditCurve.bootstrap(
                new int[]{1}, new double[]{100.0}, 0.4, d));               // no hazard fits
    }

    // ------------------------------------------------------------------ Z-spread

    @Test
    void curvePricedBondHasZeroZSpreadAndRoundTrips() {
        YieldCurve curve = flat3();
        double onCurve = CreditSpreads.priceWithZSpread(100, 0.05, 2, 5, curve, 0);
        assertEquals(0.0, CreditSpreads.zSpread(onCurve, 100, 0.05, 2, 5, curve), 1e-10);

        // A credit-risky price below the curve price implies positive z,
        // and the solved z reprices the bond exactly.
        double risky = onCurve * 0.95;
        double z = CreditSpreads.zSpread(risky, 100, 0.05, 2, 5, curve);
        assertTrue(z > 0);
        assertEquals(risky, CreditSpreads.priceWithZSpread(100, 0.05, 2, 5, curve, z), 1e-9);
    }

    @Test
    void impossiblePricesThrowInsteadOfReturningTheBracketEdge() {
        YieldCurve curve = flat3();
        // Far above the maximum attainable PV (z = -50%).
        assertThrows(IllegalArgumentException.class,
                () -> CreditSpreads.zSpread(1e7, 100, 0.05, 2, 5, curve));
        // Effectively free bond: below the z = 500% price.
        assertThrows(IllegalArgumentException.class,
                () -> CreditSpreads.zSpread(1e-9, 100, 0.05, 2, 5, curve));
    }
}
