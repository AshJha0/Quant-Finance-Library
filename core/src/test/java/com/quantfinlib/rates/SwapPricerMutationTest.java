package com.quantfinlib.rates;

import com.quantfinlib.rates.BusinessCalendar.Roll;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the SwapPricer numbers that SwapPricerTest only checks by sign or
 * symmetry. On a flat continuously compounded curve every quantity has a
 * closed form in z (own computation, written out in each test): the par
 * rate (1 - e^{-Tz}) / sum e^{-iz}, and the DV01 as the exact difference
 * of that closed form at z + 1bp and z. The multi-curve overloads are
 * pinned against the InterestRateSwap methods they wrap and against a
 * DV01 rebuilt from shifted curves by hand.
 */
@SuppressWarnings("deprecation") // SwapPricer kept through 3.x; pinned here unchanged
class SwapPricerMutationTest {

    private static final double Z = 0.04;
    private static final LocalDate VAL = LocalDate.of(2026, 11, 2);
    private static final BusinessCalendar CAL = BusinessCalendar.weekendsOnly();

    private static YieldCurve flat(double z) {
        double[] t = {1, 2, 3, 5, 7, 10};
        double[] zr = new double[t.length];
        java.util.Arrays.fill(zr, z);
        return YieldCurve.ofZeroRates(t, zr);
    }

    /** Closed-form payer PV per unit notional on a flat cc curve. */
    private static double flatPayerPv(double z, int n, double k) {
        double ann = 0;
        for (int i = 1; i <= n; i++) {
            ann += Math.exp(-z * i);
        }
        return 1 - Math.exp(-z * n) - k * ann;
    }

    private static DiscountCurve dc(double base, double slope) {
        double[] t = {0.5, 1, 2, 3, 5, 7, 10};
        double[] df = new double[t.length];
        for (int i = 0; i < t.length; i++) {
            df[i] = Math.exp(-(base + slope * t[i]) * t[i]);
        }
        return DiscountCurve.of(VAL, t, df, CurveInterpolation.LOG_LINEAR_DISCOUNT);
    }

    private static InterestRateSwap swap(double k) {
        LocalDate spot = CAL.addBusinessDays(VAL, 2);
        return new InterestRateSwap(spot, spot.plusYears(5), k, 1, DayCount.THIRTY_360, 4,
                DayCount.ACT_360, CAL, Roll.MODIFIED_FOLLOWING);
    }

    @Test
    void parRateIsTheClosedFormOnAFlatCurve() {
        YieldCurve c = flat(Z);
        for (int n : new int[]{1, 3, 5, 10}) {
            double ann = 0;
            for (int i = 1; i <= n; i++) {
                ann += Math.exp(-Z * i);
            }
            double expected = (1 - Math.exp(-Z * n)) / ann;
            assertEquals(expected, SwapPricer.parRate(c, n), 1e-14, "tenor " + n);
        }
        // One-year par rate on a cc curve is e^z - 1, a little above z.
        assertEquals(Math.expm1(Z), SwapPricer.parRate(c, 1), 1e-14);
        // At par the payer PV vanishes exactly.
        assertEquals(0, SwapPricer.payerPv(c, 7, SwapPricer.parRate(c, 7)), 1e-14);
    }

    @Test
    void dv01IsTheExactClosedFormDifference() {
        YieldCurve c = flat(Z);
        for (double k : new double[]{0.0, 0.03, 0.06}) {
            double expected = flatPayerPv(Z + 1e-4, 5, k) - flatPayerPv(Z, 5, k);
            double got = SwapPricer.dv01(c, 5, k);
            assertEquals(expected, got, 1e-13, "K=" + k);
            assertTrue(got > 0, "payer DV01 must be positive");
        }
        // Order of magnitude: about annuity * e^z * 1bp for a par swap (class javadoc).
        double par = SwapPricer.parRate(c, 5);
        double approx = SwapPricer.annuity(c, 5) * Math.exp(Z) * 1e-4;
        assertEquals(approx, SwapPricer.dv01(c, 5, par), 0.02 * approx);
    }

    @Test
    void parallelBumpShiftsEveryPillarZeroRate() {
        double[] t = {1, 2, 5, 10};
        double[] z = {0.01, 0.02, 0.03, 0.035};
        YieldCurve c = YieldCurve.ofZeroRates(t, z);
        YieldCurve b = SwapPricer.parallelBump(c, 0.0025);
        assertEquals(c.tenors(), b.tenors());
        for (int i = 0; i < t.length; i++) {
            assertEquals(z[i] + 0.0025, b.zeroRate(t[i]), 1e-15, "pillar " + t[i]);
        }
    }

    @Test
    void multiCurveOverloadsDelegateToTheSwap() {
        DiscountCurve ois = dc(0.025, 0.001);
        DiscountCurve ibor = dc(0.029, 0.001);
        InterestRateSwap s = swap(0.03);
        assertEquals(s.annuity(ois), SwapPricer.annuity(ois, s), 0);
        assertTrue(SwapPricer.annuity(ois, s) > 4);
        assertEquals(s.parRate(ois, ibor), SwapPricer.parRate(ois, ibor, s), 0);
        assertTrue(SwapPricer.parRate(ois, ibor, s) > 0.02);
        assertEquals(s.payerPv(ois, ibor), SwapPricer.payerPv(ois, ibor, s), 0);
        assertNotEquals(0, SwapPricer.payerPv(ois, ibor, s));
    }

    @Test
    void multiCurveDv01ShiftsBothCurves() {
        DiscountCurve ois = dc(0.025, 0.001);
        DiscountCurve ibor = dc(0.029, 0.001);
        InterestRateSwap s = swap(0.03);
        double base = s.payerPv(ois, ibor);
        double both = s.payerPv(ois.parallelShift(1e-4), ibor.parallelShift(1e-4)) - base;
        assertEquals(both, SwapPricer.dv01(ois, ibor, s), 1e-15);
        // Shifting only the discount curve gives a different (wrong) answer.
        double discOnly = s.payerPv(ois.parallelShift(1e-4), ois.parallelShift(1e-4)) - base;
        assertNotEquals(discOnly, SwapPricer.dv01(ois, ibor, s), 1e-9);
        assertTrue(SwapPricer.dv01(ois, ibor, s) > 0);
        // Single-curve case: the same shifted curve on both legs.
        double base1 = s.payerPv(ois, ois);
        DiscountCurve d = ois.parallelShift(1e-4);
        assertEquals(s.payerPv(d, d) - base1, SwapPricer.dv01(ois, ois, s), 1e-15);
    }
}
