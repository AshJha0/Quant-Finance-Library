package com.quantfinlib.credit;

import com.quantfinlib.rates.YieldCurve;
import com.quantfinlib.stats.SpecialFunctions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exposure generator pinned against closed forms: a swap's discounted EE
 * at a reset date is a European swaption (Jamshidian 1989 under
 * Hull-White), an FX forward's is a Black call, deterministic limits are
 * exact, and E[D V] is the time-0 value at every date (martingale).
 * Monte Carlo checks allow four standard errors.
 */
@SuppressWarnings("removal") // exercises 2.x API deprecated for removal in 2.4.0
class ExposureSimulatorTest {

    private static final double A = 0.05;
    private static final double SIG = 0.01;

    private static YieldCurve dom() {
        return YieldCurve.ofZeroRates(new double[]{0.5, 1, 2, 3, 5, 7, 10},
                new double[]{0.025, 0.027, 0.029, 0.031, 0.033, 0.034, 0.035});
    }

    private static YieldCurve fgn() {
        return YieldCurve.ofZeroRates(new double[]{1, 5, 10}, new double[]{0.01, 0.012, 0.015});
    }

    private static final ExposureSimulator.Swap PAYER =
            new ExposureSimulator.Swap(100, 0.032, true, 0, 5, 1);

    private static double p0(double t) {
        return t == 0 ? 1 : dom().discountFactor(t);
    }

    private static double bFn(double tau) {
        return (1 - Math.exp(-A * tau)) / A;
    }

    /** Hull-White zero-bond put, Brigo-Mercurio eq. 3.41. */
    private static double zbp(double t, double s, double strike) {
        double sp = SIG * Math.sqrt((1 - Math.exp(-2 * A * t)) / (2 * A)) * bFn(s - t);
        double h = Math.log(p0(s) / (p0(t) * strike)) / sp + sp / 2;
        return strike * p0(t) * SpecialFunctions.normCdf(-h + sp) - p0(s) * SpecialFunctions.normCdf(-h);
    }

    /** P(t,T) given x(t) under HW (for the Jamshidian strike). */
    private static double bond(double t, double s, double x) {
        double v = vFn(s - t) - vFn(s) + vFn(t);
        return p0(s) / p0(t) * Math.exp(0.5 * v - bFn(s - t) * x);
    }

    private static double vFn(double tau) {
        return SIG * SIG / (A * A) * (tau + 2 / A * Math.exp(-A * tau)
                - 1 / (2 * A) * Math.exp(-2 * A * tau) - 3 / (2 * A));
    }

    /** Payer swaption expiring T_k on the remaining annual swap to 5y, per 100 notional. */
    private static double payerSwaption(int k, double strike) {
        double lo = -1, hi = 1;
        for (int it = 0; it < 200; it++) {
            double mid = 0.5 * (lo + hi);
            double sum = 0;
            for (int j = k + 1; j <= 5; j++) {
                sum += strike * bond(k, j, mid) + (j == 5 ? bond(k, j, mid) : 0);
            }
            if (sum > 1) {
                lo = mid;   // bond prices fall as x rises
            } else {
                hi = mid;
            }
        }
        double xs = 0.5 * (lo + hi);
        double price = 0;
        for (int j = k + 1; j <= 5; j++) {
            double c = strike + (j == 5 ? 1 : 0);
            price += c * zbp(k, j, bond(k, j, xs));
        }
        return 100 * price;
    }

    @Test
    void swapExpectedExposureIsTheSwaptionAtEachReset() {
        ExposureProfile prof = ExposureSimulator.simulate(
                ExposureSimulator.Market.ratesOnly(dom(), A, SIG), List.of(PAYER), List.of(), null,
                new double[]{1, 2, 3, 4}, 40_000, 7L);
        for (int i = 0; i < 4; i++) {
            double ref = payerSwaption(i + 1, 0.032);
            double se = prof.discountedExpectedExposureStdError(i);
            assertEquals(ref, prof.discountedExpectedExposure(i), 4 * se + 1e-9,
                    "t=" + (i + 1) + " se=" + se);
            assertTrue(ref > 0.1, "non-trivial reference " + ref);
        }
    }

    @Test
    void zeroVolatilityGivesTheForwardValue() {
        // Exposure dates between resets exercise the fixed floating coupon.
        double[] times = {0.5, 1.5, 2.25, 4.75};
        ExposureProfile prof = ExposureSimulator.simulate(
                ExposureSimulator.Market.ratesOnly(dom(), A, 0), List.of(PAYER), List.of(), null,
                times, 10, 1L);
        for (int i = 0; i < times.length; i++) {
            double t = times[i];
            int j = (int) Math.ceil(t);   // current period ends at j
            double fixed = 0;
            for (int k = j; k <= 5; k++) {
                fixed += p0(k) / p0(t);
            }
            double fltCoupon = p0(j - 1) / p0(j);   // 1 / P(T_{j-1}, T_j) on the forward curve
            double flt = fltCoupon * p0(j) / p0(t) - p0(5) / p0(t);
            double v = 100 * (flt - 0.032 * fixed);
            assertEquals(Math.max(v, 0), prof.expectedExposure(i), 1e-10);
            assertEquals(Math.max(-v, 0), prof.expectedNegativeExposure(i), 1e-10);
            assertEquals(Math.max(v, 0), prof.pfe(i, 0.99), 1e-10);
        }
    }

    @Test
    void fxForwardExpectedExposureIsABlackCall() {
        double s0 = 1.10, vol = 0.12, k = 1.15;
        ExposureSimulator.Market mk = new ExposureSimulator.Market(dom(), A, 0, fgn(), s0, vol, 0);
        ExposureSimulator.FxForward fwd = new ExposureSimulator.FxForward(100, k, 3);
        double[] times = {0.5, 1, 2, 2.9};
        ExposureProfile prof = ExposureSimulator.simulate(mk, List.of(), List.of(fwd), null,
                times, 40_000, 11L);
        for (int i = 0; i < times.length; i++) {
            double t = times[i];
            double fwdRate = s0 * fgn().discountFactor(3) / p0(3);
            double sd = vol * Math.sqrt(t);
            double d1 = (Math.log(fwdRate / k) + 0.5 * sd * sd) / sd;
            double ref = 100 * p0(3) * (fwdRate * SpecialFunctions.normCdf(d1) - k * SpecialFunctions.normCdf(d1 - sd));
            assertEquals(ref, prof.discountedExpectedExposure(i),
                    4 * prof.discountedExpectedExposureStdError(i), "t=" + t);
        }
    }

    @Test
    void discountedValueIsAMartingaleWithCorrelatedFactors() {
        ExposureSimulator.Market mk = new ExposureSimulator.Market(dom(), A, SIG, fgn(), 1.10, 0.10, -0.4);
        ExposureSimulator.FxForward fwd = new ExposureSimulator.FxForward(-100, 1.12, 4);
        ExposureProfile prof = ExposureSimulator.simulate(mk, List.of(), List.of(fwd), null,
                new double[]{1, 2, 3}, 40_000, 3L);
        double v0 = -100 * (1.10 * fgn().discountFactor(4) - 1.12 * p0(4));
        for (int i = 0; i < 3; i++) {
            double ev = prof.discountedExpectedExposure(i) - prof.discountedExpectedNegativeExposure(i);
            // Both parts have similar error; bound by the larger leg's 4 SE scaled by 2.
            assertEquals(v0, ev, 8 * 100 * 0.10 * Math.sqrt(i + 1) / Math.sqrt(40_000), "t=" + (i + 1));
        }
    }

    @Test
    void collateralReducesExposure() {
        ExposureSimulator.Market mk = ExposureSimulator.Market.ratesOnly(dom(), A, SIG);
        double[] times = {0.5, 1, 1.5, 2, 2.5, 3};
        ExposureProfile bare = ExposureSimulator.simulate(mk, List.of(PAYER), List.of(), null, times, 5_000, 5L);
        ExposureProfile mpr = ExposureSimulator.simulate(mk, List.of(PAYER), List.of(),
                new ExposureSimulator.Csa(0, 0, 10 / 250.0), times, 5_000, 5L);
        ExposureProfile perfect = ExposureSimulator.simulate(mk, List.of(PAYER), List.of(),
                new ExposureSimulator.Csa(0, 0, 0), times, 5_000, 5L);
        ExposureProfile loose = ExposureSimulator.simulate(mk, List.of(PAYER), List.of(),
                new ExposureSimulator.Csa(1e9, 0, 10 / 250.0), times, 5_000, 5L);
        ExposureProfile mta = ExposureSimulator.simulate(mk, List.of(PAYER), List.of(),
                new ExposureSimulator.Csa(1, 0.5, 10 / 250.0), times, 5_000, 5L);
        for (int i = 0; i < times.length; i++) {
            assertEquals(0, perfect.expectedExposure(i), 0);
            // The lag dates refine the grid, so the draws differ: compare within MC error.
            double se = Math.hypot(bare.discountedExpectedExposureStdError(i),
                    loose.discountedExpectedExposureStdError(i));
            assertEquals(bare.discountedExpectedExposure(i), loose.discountedExpectedExposure(i), 4 * se);
            assertTrue(mpr.expectedExposure(i) < 0.5 * bare.expectedExposure(i));
            assertTrue(mta.expectedExposure(i) < bare.expectedExposure(i));
        }
        assertTrue(bare.epe() > mpr.epe());
        // Threshold and MTA leave more uncollateralised exposure than a zero-threshold CSA.
        assertTrue(mta.epe() > mpr.epe(), mta.epe() + " vs " + mpr.epe());
        assertTrue(bare.pfe(3, 0.99) >= bare.pfe(3, 0.95));
        assertTrue(bare.pfe(3, 0.95) > bare.expectedExposure(3));
    }

    @Test
    void cvaDvaAndWrongWay() {
        ExposureSimulator.Market mk = ExposureSimulator.Market.ratesOnly(dom(), A, SIG);
        double[] times = new double[20];
        for (int i = 0; i < 20; i++) {
            times[i] = 0.25 * (i + 1);
        }
        ExposureSimulator.Swap receiver = new ExposureSimulator.Swap(100, 0.032, false, 0, 5, 1);
        ExposureProfile payer = ExposureSimulator.simulate(mk, List.of(PAYER), List.of(), null, times, 4_000, 9L);
        ExposureProfile recv = ExposureSimulator.simulate(mk, List.of(receiver), List.of(), null, times, 4_000, 9L);
        CreditCurve cpty = CreditCurve.bootstrap(new int[]{5}, new double[]{0.02}, 0.4, dom());
        double cva = CvaApproximator.cva(payer, cpty, 0.6);
        assertTrue(cva > 0);
        // Same paths: the receiver's positive exposure is the payer's negative exposure.
        assertEquals(CvaApproximator.cva(recv, cpty, 0.6), CvaApproximator.dva(payer, cpty, 0.6), 1e-12);
        // rho = 0 reproduces the independent CVA; positive rho is wrong-way for the payer.
        double ww0 = CvaApproximator.cvaWrongWay(payer, cpty, 0.6, 0, ExposureProfile.Factor.RATES);
        assertEquals(cva, ww0, 1e-12 * cva);
        double wwPos = CvaApproximator.cvaWrongWay(payer, cpty, 0.6, 0.5, ExposureProfile.Factor.RATES);
        double wwNeg = CvaApproximator.cvaWrongWay(payer, cpty, 0.6, -0.5, ExposureProfile.Factor.RATES);
        assertTrue(wwPos > 1.2 * cva, wwPos + " vs " + cva);
        assertTrue(wwNeg < 0.8 * cva, wwNeg + " vs " + cva);
        // Deterministic rates: the profile overload equals the array form with the curve's DF.
        ExposureProfile det = ExposureSimulator.simulate(ExposureSimulator.Market.ratesOnly(dom(), A, 0),
                List.of(PAYER), List.of(), null, times, 2, 1L);
        assertEquals(CvaApproximator.cva(det.expectedExposureCopy(), det.timesCopy(), cpty, dom(), 0.6),
                CvaApproximator.cva(det, cpty, 0.6), 1e-12);
        assertThrows(IllegalArgumentException.class,
                () -> CvaApproximator.cvaWrongWay(payer, cpty, 0.6, 1, ExposureProfile.Factor.RATES));
        assertThrows(IllegalArgumentException.class, () -> CvaApproximator.dva(payer, cpty, 0));
    }

    @Test
    void validationAndDeterminism() {
        ExposureSimulator.Market mk = ExposureSimulator.Market.ratesOnly(dom(), A, SIG);
        ExposureProfile a = ExposureSimulator.simulate(mk, List.of(PAYER), List.of(), null, new double[]{1, 2}, 100, 42L);
        ExposureProfile b = ExposureSimulator.simulate(mk, List.of(PAYER), List.of(), null, new double[]{1, 2}, 100, 42L);
        assertEquals(a.discountedExpectedExposure(1), b.discountedExpectedExposure(1), 0);
        assertEquals(100, a.paths());
        assertEquals(2, a.timesLength());
        assertEquals(2.0, a.time(1));
        assertThrows(IllegalArgumentException.class, () -> a.pfe(0, 1));
        assertThrows(IllegalArgumentException.class, () -> ExposureSimulator.simulate(mk, List.of(), List.of(),
                null, new double[]{1}, 10, 1L));
        assertThrows(IllegalArgumentException.class, () -> ExposureSimulator.simulate(mk, List.of(PAYER),
                List.of(), null, new double[]{2, 1}, 10, 1L));
        assertThrows(IllegalArgumentException.class, () -> ExposureSimulator.simulate(mk, List.of(),
                List.of(new ExposureSimulator.FxForward(1, 1, 1)), null, new double[]{1}, 10, 1L));
        assertThrows(IllegalArgumentException.class, () -> new ExposureSimulator.Swap(100, 0.03, true, 2, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ExposureSimulator.Csa(-1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> ExposureSimulator.Market.ratesOnly(dom(), 0, 0.01));
    }
}
