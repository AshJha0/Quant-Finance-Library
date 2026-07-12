package com.quantfinlib.rates;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SwapPricer pinned by its own linear algebra: PV is annuity * (par - K),
 * so payer/receiver symmetry around par is EXACT, the annuity on a flat
 * cc curve is a hand-summable geometric series, and PV falls one-for-one
 * (times the annuity) in the fixed rate. Complements the par-identity and
 * DV01 pins in markets.AssetClassRoundTest.
 */
class SwapPricerTest {

    private static YieldCurve flat5() {
        double[] t = {1, 2, 3, 4, 5, 7, 10};
        double[] z = new double[t.length];
        java.util.Arrays.fill(z, 0.05);
        return YieldCurve.ofZeroRates(t, z);
    }

    @Test
    void payerPvIsExactlyAntisymmetricAroundPar() {
        // payerPv(K) = annuity * (par - K): equal strikes above and below
        // par give PVs of equal size and opposite sign, and the receiver
        // (the documented negation) is the mirror trade.
        YieldCurve c = flat5();
        double par = SwapPricer.parRate(c, 5);
        double d = 0.01;
        double below = SwapPricer.payerPv(c, 5, par - d);
        double above = SwapPricer.payerPv(c, 5, par + d);
        assertEquals(-below, above, 1e-15, "linear in K: exact antisymmetry");
        assertEquals(SwapPricer.annuity(c, 5) * d, below, 1e-15);
        assertTrue(below > 0, "paying below par is an asset to the payer");
        assertTrue(above < 0, "paying above par is a liability to the payer");
    }

    @Test
    void annuityOnAFlatCurveIsTheHandGeometricSum() {
        // Flat 5% cc: DF(i) = e^{-0.05 i}, so the 5y annual annuity is
        // e^{-0.05} + e^{-0.10} + e^{-0.15} + e^{-0.20} + e^{-0.25}.
        YieldCurve c = flat5();
        double expected = 0;
        for (int i = 1; i <= 5; i++) {
            expected += Math.exp(-0.05 * i);
        }
        assertEquals(expected, SwapPricer.annuity(c, 5), 1e-12);
        assertTrue(SwapPricer.annuity(c, 1) > 0);
        // Longer tenor adds strictly positive discount factors.
        assertTrue(SwapPricer.annuity(c, 10) > SwapPricer.annuity(c, 5));
    }

    @Test
    void payerPvFallsStrictlyAsTheFixedRateRises() {
        YieldCurve c = flat5();
        double prev = Double.POSITIVE_INFINITY;
        for (double k = 0.01; k <= 0.09; k += 0.02) {
            double pv = SwapPricer.payerPv(c, 5, k);
            assertTrue(pv < prev, "paying a higher fixed rate must cost more, K=" + k);
            prev = pv;
        }
    }

    @Test
    void swapGatesRefuseNonsense() {
        YieldCurve c = flat5();
        assertThrows(IllegalArgumentException.class, () -> SwapPricer.annuity(c, 0));
        assertThrows(IllegalArgumentException.class, () -> SwapPricer.parRate(c, -1));
        assertThrows(IllegalArgumentException.class,
                () -> SwapPricer.payerPv(c, 5, Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> SwapPricer.payerPv(c, 0, 0.05));
    }
}
