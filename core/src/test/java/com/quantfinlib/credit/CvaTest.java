package com.quantfinlib.credit;

import com.quantfinlib.rates.YieldCurve;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CVA pinned by hand: flat exposure on a flat-hazard curve is a sum of
 * exponentials anyone can recompute, a riskless counterparty costs
 * (essentially) nothing, and more spread always means more CVA.
 */
class CvaTest {

    private static YieldCurve flat3() {
        return YieldCurve.ofZeroRates(new double[]{1, 2, 3, 5, 7, 10},
                new double[]{0.03, 0.03, 0.03, 0.03, 0.03, 0.03});
    }

    private static double[] quarterlyGrid(double years) {
        int n = (int) Math.round(years * 4);
        double[] t = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = (i + 1) * 0.25;
        }
        return t;
    }

    @Test
    void flatExposureFlatHazardMatchesTheHandSum() {
        YieldCurve discount = flat3();
        // Single 10y pillar: the bootstrapped hazard is flat, so
        // Q(t) = e^{-h t} exactly and the CVA sum is pure arithmetic.
        CreditCurve curve = CreditCurve.bootstrap(new int[]{10},
                new double[]{0.02}, 0.40, discount);
        double h = curve.hazard(1.0);
        double lgd = 0.6;
        double[] grid = quarterlyGrid(5);
        double[] ee = new double[grid.length];
        java.util.Arrays.fill(ee, 1_000_000);

        double expected = 0;
        double prevT = 0;
        for (double t : grid) {
            expected += 1_000_000 * (Math.exp(-h * prevT) - Math.exp(-h * t))
                    * Math.exp(-0.03 * t);
            prevT = t;
        }
        expected *= lgd;
        assertEquals(expected, CvaApproximator.cva(ee, grid, curve, discount, lgd), 1e-6);
        // Order of magnitude sanity: LGD * EE * 5y default probability,
        // lightly discounted — tens of thousands on a million of EE.
        assertTrue(expected > 50_000 && expected < 120_000, "cva=" + expected);
    }

    @Test
    void risklessCounterpartyCostsNothing() {
        YieldCurve discount = flat3();
        // The bootstrap gates demand a positive spread; a vanishing one is
        // the riskless limit and the CVA must vanish with it.
        CreditCurve curve = CreditCurve.bootstrap(new int[]{5},
                new double[]{1e-8}, 0.40, discount);
        double[] grid = quarterlyGrid(5);
        double[] ee = new double[grid.length];
        java.util.Arrays.fill(ee, 1.0);
        assertEquals(0, CvaApproximator.cva(ee, grid, curve, discount, 0.6), 1e-6);
    }

    @Test
    void cvaIncreasesWithTheSpreadLevel() {
        YieldCurve discount = flat3();
        CreditCurve tight = CreditCurve.bootstrap(new int[]{1, 3, 5},
                new double[]{0.01, 0.01, 0.01}, 0.40, discount);
        CreditCurve wide = CreditCurve.bootstrap(new int[]{1, 3, 5},
                new double[]{0.02, 0.02, 0.02}, 0.40, discount);
        double[] grid = quarterlyGrid(5);
        double[] ee = new double[grid.length];
        java.util.Arrays.fill(ee, 1_000_000);
        double cvaTight = CvaApproximator.cva(ee, grid, tight, discount, 0.6);
        double cvaWide = CvaApproximator.cva(ee, grid, wide, discount, 0.6);
        assertTrue(cvaTight > 0);
        assertTrue(cvaWide > cvaTight,
                "200bp CVA " + cvaWide + " must exceed 100bp CVA " + cvaTight);
        // Not quite 2x (survival decays faster on the wide curve), but close.
        assertTrue(cvaWide < 2 * cvaTight);
    }

    @Test
    void gatesRefuseNonsense() {
        YieldCurve discount = flat3();
        CreditCurve curve = CreditCurve.bootstrap(new int[]{5},
                new double[]{0.01}, 0.40, discount);
        double[] okT = {0.5, 1.0};
        double[] okE = {1.0, 1.0};
        assertThrows(IllegalArgumentException.class,     // empty
                () -> CvaApproximator.cva(new double[0], new double[0], curve, discount, 0.6));
        assertThrows(IllegalArgumentException.class,     // misaligned
                () -> CvaApproximator.cva(okE, new double[]{1.0}, curve, discount, 0.6));
        assertThrows(IllegalArgumentException.class,     // non-ascending times
                () -> CvaApproximator.cva(okE, new double[]{1.0, 0.5}, curve, discount, 0.6));
        assertThrows(IllegalArgumentException.class,     // t[0] = 0
                () -> CvaApproximator.cva(okE, new double[]{0, 1.0}, curve, discount, 0.6));
        assertThrows(IllegalArgumentException.class,     // NaN time
                () -> CvaApproximator.cva(okE, new double[]{0.5, Double.NaN}, curve, discount, 0.6));
        assertThrows(IllegalArgumentException.class,     // negative exposure
                () -> CvaApproximator.cva(new double[]{-1, 1}, okT, curve, discount, 0.6));
        assertThrows(IllegalArgumentException.class,     // NaN exposure
                () -> CvaApproximator.cva(new double[]{Double.NaN, 1}, okT, curve, discount, 0.6));
        assertThrows(IllegalArgumentException.class,     // lgd = 0
                () -> CvaApproximator.cva(okE, okT, curve, discount, 0));
        assertThrows(IllegalArgumentException.class,     // lgd > 1
                () -> CvaApproximator.cva(okE, okT, curve, discount, 1.2));
    }
}
