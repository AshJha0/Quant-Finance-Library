package com.quantfinlib.rates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Pins ShortRateModels, KeyRateDurations and YieldCurve to exact values so
 * that mutation testing of the rates package sees them. Expected values are
 * independent computations: the closed forms of Brigo and Mercurio,
 * Interest Rate Models (2006), eq. 3.8 (Vasicek) and 3.25 (CIR), evaluated
 * separately in double precision, plus limits that hold by construction.
 */
class ShortRateAndKeyRateMutationTest {

    private static final double TOL = 1e-12;

    @Test
    void vasicekBondMatchesClosedForm() {
        // r=3%, a=0.1, b=5%, sigma=1%, T=5 -> 0.843791331932963 (own computation of eq. 3.8)
        assertEquals(0.843791331932963, ShortRateModels.vasicekBond(0.03, 0.1, 0.05, 0.01, 5), TOL);
        assertEquals(-Math.log(0.843791331932963) / 5,
                ShortRateModels.vasicekYield(0.03, 0.1, 0.05, 0.01, 5), TOL);
    }

    @Test
    void vasicekWithZeroVolIsDeterministic() {
        double a = 0.3, b = 0.06, r = 0.02, t = 4;
        double bt = (1 - Math.exp(-a * t)) / a;
        double expected = Math.exp(-(b * t + (r - b) * bt));
        assertEquals(expected, ShortRateModels.vasicekBond(r, a, b, 0, t), TOL);
    }

    @Test
    void vasicekStepIsExactConditionalMoments() {
        double a = 0.5, b = 0.04, s = 0.02, dt = 0.25, r = 0.01;
        double decay = Math.exp(-a * dt);
        double mean = b + (r - b) * decay;
        double sd = s * Math.sqrt((1 - decay * decay) / (2 * a));
        assertEquals(mean, ShortRateModels.vasicekStep(r, a, b, s, dt, 0), TOL);
        assertEquals(mean + 1.5 * sd, ShortRateModels.vasicekStep(r, a, b, s, dt, 1.5), TOL);
    }

    @Test
    void cirBondMatchesClosedForm() {
        // r=4%, a=0.5, b=6%, sigma=0.1, T=3 -> 0.8623058344637996 (own computation of eq. 3.25)
        assertEquals(0.8623058344637996, ShortRateModels.cirBond(0.04, 0.5, 0.06, 0.1, 3), TOL);
    }

    @Test
    void cirBondApproachesDeterministicAsVolVanishes() {
        double a = 0.3, b = 0.06, r = 0.02, t = 4;
        double bt = (1 - Math.exp(-a * t)) / a;
        double expected = Math.exp(-(b * t + (r - b) * bt));
        assertEquals(expected, ShortRateModels.cirBond(r, a, b, 1e-3, t), 1e-5);
    }

    @Test
    void cirFellerAndStep() {
        assertEquals(2 * 0.5 * 0.06 / 0.01, ShortRateModels.cirFeller(0.5, 0.06, 0.1), TOL);
        double r = 0.04, a = 0.5, b = 0.06, s = 0.1, dt = 0.01;
        assertEquals(r + a * (b - r) * dt + s * Math.sqrt(r * dt) * 0.7,
                ShortRateModels.cirStep(r, a, b, s, dt, 0.7), TOL);
        // Negative rate: diffusion off, drift uses the floored rate 0.
        assertEquals(-0.01 + a * b * dt, ShortRateModels.cirStep(-0.01, a, b, s, dt, 3.0), TOL);
    }

    @Test
    void validationRejectsBadInputs() {
        assertThrows(IllegalArgumentException.class, () -> ShortRateModels.vasicekBond(0.01, 0, 0.05, 0.01, 1));
        assertThrows(IllegalArgumentException.class, () -> ShortRateModels.vasicekBond(0.01, 0.1, 0.05, -0.01, 1));
        assertThrows(IllegalArgumentException.class, () -> ShortRateModels.vasicekBond(0.01, 0.1, 0.05, 0.01, 0));
        assertThrows(IllegalArgumentException.class,
                () -> ShortRateModels.vasicekBond(0.01, Double.POSITIVE_INFINITY, 0.05, 0.01, 1));
        assertThrows(IllegalArgumentException.class,
                () -> ShortRateModels.vasicekBond(0.01, 0.1, 0.05, Double.POSITIVE_INFINITY, 1));
        assertThrows(IllegalArgumentException.class, () -> ShortRateModels.cirBond(-0.01, 0.1, 0.05, 0.1, 1));
        assertThrows(IllegalArgumentException.class, () -> ShortRateModels.cirBond(0.01, 0.1, 0.05, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> ShortRateModels.cirBond(0.01, -1, 0.05, 0.1, 1));
        assertThrows(IllegalArgumentException.class, () -> ShortRateModels.cirBond(0.01, 0.1, 0.05, 0.1, -1));
        YieldCurve flat = YieldCurve.ofZeroRates(new double[] {1}, new double[] {0.03});
        assertThrows(IllegalArgumentException.class, () -> ShortRateModels.hullWhiteBond(flat, -1, 1, 0.03, 0.1, 0.01));
        assertThrows(IllegalArgumentException.class, () -> ShortRateModels.hullWhiteBond(flat, 0, 0, 0.03, 0.1, 0.01));
        assertThrows(IllegalArgumentException.class, () -> ShortRateModels.hullWhiteBond(flat, 0, 1, 0.03, 0, 0.01));
        assertThrows(IllegalArgumentException.class, () -> ShortRateModels.hullWhiteBond(flat, 0, 1, 0.03, 0.1, -1));
        assertThrows(IllegalArgumentException.class, () -> ShortRateModels.instantaneousForward(flat, -0.1));
    }

    @Test
    void instantaneousForwardIsExactOnLinearZeroCurve() {
        // z(t) = 0.01 + 0.002 t, so -ln P = 0.01 t + 0.002 t^2 and f(t) = 0.01 + 0.004 t.
        YieldCurve c = YieldCurve.ofZeroRates(new double[] {1e-9, 40},
                new double[] {0.01 + 0.002e-9, 0.01 + 0.002 * 40});
        assertEquals(0.01 + 0.004 * 7, ShortRateModels.instantaneousForward(c, 7), 1e-9);
        assertEquals(0.01 + 0.004 * 0.001, ShortRateModels.instantaneousForward(c, 0.001), 1e-9);
        assertEquals(0.01, ShortRateModels.instantaneousForward(c, 0), 1e-9);
    }

    @Test
    void hullWhiteRepricesTheCurve() {
        YieldCurve flat = YieldCurve.ofZeroRates(new double[] {0.5, 30}, new double[] {0.03, 0.03});
        // At t=0 with r0 = f(0) the model returns the market discount factor.
        assertEquals(Math.exp(-0.03 * 5), ShortRateModels.hullWhiteBond(flat, 0, 5, 0.03, 0.1, 0.01), 1e-9);
        // At t=2 with sigma=0 and r = f(2): forward discount factor.
        assertEquals(Math.exp(-0.03 * 5), ShortRateModels.hullWhiteBond(flat, 2, 5, 0.03, 0.1, 0), 1e-9);
        // At t=2 with sigma>0 and a rate off the forward (Brigo-Mercurio eq. 3.39).
        double a = 0.1, s = 0.01, t = 2, tau = 5, r = 0.05;
        double bt = (1 - Math.exp(-a * tau)) / a;
        double var = s * s * (1 - Math.exp(-2 * a * t)) / (4 * a);
        double expected = Math.exp(-0.03 * tau) * Math.exp(bt * (0.03 - r) - var * bt * bt);
        assertEquals(expected, ShortRateModels.hullWhiteBond(flat, t, tau, r, a, s), 1e-9);
    }

    @Test
    void keyRateDv01sOnNodeAlignedCashFlows() {
        double[] tenors = {1, 2, 3, 4, 5};
        double[] zs = {0.02, 0.025, 0.03, 0.035, 0.04};
        YieldCurve c = YieldCurve.ofZeroRates(tenors, zs);
        double face = 100, cpn = 0.05;
        double[] krd = KeyRateDurations.keyRateDv01s(face, cpn, 1, 5, c);
        double sum = 0;
        for (int i = 0; i < 5; i++) {
            double cf = face * cpn + (i == 4 ? face : 0);
            double t = tenors[i];
            // Central difference of cf*exp(-(z +- h) t) over 2: cf*DF*sinh(h t).
            double expected = cf * Math.exp(-zs[i] * t) * Math.sinh(1e-4 * t);
            assertEquals(expected, krd[i], 1e-10);
            sum += expected;
        }
        assertEquals(sum, KeyRateDurations.parallelDv01(face, cpn, 1, 5, c), 1e-10);
        // The input curve is untouched.
        assertEquals(0.03, c.zeroRate(3), TOL);
    }

    @Test
    void yieldCurveInterpolationAndForwards() {
        YieldCurve c = YieldCurve.ofZeroRates(new double[] {1, 3}, new double[] {0.02, 0.04});
        assertEquals(0.02, c.zeroRate(0.5), TOL);
        assertEquals(0.04, c.zeroRate(10), TOL);
        assertEquals(0.03, c.zeroRate(2), TOL);
        assertEquals(0.035, c.zeroRate(2.5), TOL);
        assertEquals(1.0, c.discountFactor(0), TOL);
        assertEquals(1.0, c.discountFactor(-1), TOL);
        assertEquals(Math.exp(-0.06), c.discountFactor(2), TOL);
        assertEquals((0.12 - 0.02) / 2, c.forwardRate(1, 3), TOL);
        assertThrows(IllegalArgumentException.class, () -> c.forwardRate(2, 2));
        assertThrows(IllegalArgumentException.class, () -> c.forwardRate(3, 2));
        assertThrows(IllegalArgumentException.class,
                () -> YieldCurve.ofZeroRates(new double[] {1}, new double[] {0.01, 0.02}));
        assertThrows(IllegalArgumentException.class, () -> YieldCurve.ofZeroRates(new double[0], new double[0]));
        assertThrows(IllegalArgumentException.class,
                () -> YieldCurve.ofZeroRates(new double[] {0}, new double[] {0.01}));
    }

    @Test
    void parSwapBootstrapByHand() {
        // Par rates 2% at 1y, 4% at 3y; 2y interpolated to 3%.
        YieldCurve c = YieldCurve.bootstrapAnnualParSwaps(new int[] {1, 3}, new double[] {0.02, 0.04});
        double df1 = 1 / 1.02;
        double df2 = (1 - 0.03 * df1) / 1.03;
        double df3 = (1 - 0.04 * (df1 + df2)) / 1.04;
        assertEquals(df1, c.discountFactor(1), TOL);
        assertEquals(df2, c.discountFactor(2), TOL);
        assertEquals(df3, c.discountFactor(3), TOL);
        // Pillar after the first given tenor, with a short leading pillar.
        YieldCurve d = YieldCurve.bootstrapAnnualParSwaps(new int[] {2, 4}, new double[] {0.03, 0.05});
        double e1 = 1 / 1.03;
        double e2 = (1 - 0.03 * e1) / 1.03;
        double e3 = (1 - 0.04 * (e1 + e2)) / 1.04;
        assertEquals(e3, d.discountFactor(3), TOL);
        assertThrows(IllegalArgumentException.class,
                () -> YieldCurve.bootstrapAnnualParSwaps(new int[] {1}, new double[] {0.01, 0.02}));
        assertThrows(IllegalArgumentException.class,
                () -> YieldCurve.bootstrapAnnualParSwaps(new int[0], new double[0]));
        assertThrows(IllegalArgumentException.class,
                () -> YieldCurve.bootstrapAnnualParSwaps(new int[] {1, 2}, new double[] {0.01, 5.0}));
    }
}
