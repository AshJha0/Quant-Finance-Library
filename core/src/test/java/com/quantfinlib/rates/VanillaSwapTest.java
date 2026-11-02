package com.quantfinlib.rates;

import com.quantfinlib.rates.BusinessCalendar.Roll;
import com.quantfinlib.rates.BusinessCalendar.StubType;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Convention-driven legs pinned by independent identities: the OIS
 * telescoping identity, equality with {@link OisSwap} and
 * {@link InterestRateSwap} in their stated special cases, the par-rate
 * identity, and the effect of each convention. QuantLib cross-checks are in
 * {@link QuantLibGoldenTest}.
 */
class VanillaSwapTest {

    private static final LocalDate VAL = LocalDate.of(2026, 11, 2);
    private static final BusinessCalendar CAL = BusinessCalendar.weekendsOnly();
    private static final Roll MF = Roll.MODIFIED_FOLLOWING;
    private static final LocalDate SPOT = CAL.addBusinessDays(VAL, 2);
    private static final DiscountCurve OIS = MultiCurveBootstrap.bootstrap(VAL, MultiCurveTest.oisInstruments(),
            CurveInterpolation.LOG_LINEAR_DISCOUNT);
    private static final LegConvention ANNUAL = LegConvention.of(1, DayCount.ACT_360, CAL, MF);

    private static OvernightLeg leg(LocalDate s, LocalDate e, LegConvention c, OvernightConvention on) {
        return new OvernightLeg(s, e, 0, c, on, FixingHistory.empty());
    }

    @Test
    void inArrearsLegTelescopesToDiscountFactorDifference() {
        LocalDate end = SPOT.plusYears(7);
        OvernightLeg l = leg(SPOT, end, ANNUAL, OvernightConvention.IN_ARREARS);
        double expected = OIS.discountFactor(SPOT) - OIS.discountFactor(CAL.roll(end, MF));
        assertEquals(expected, l.pv(OIS, OIS), 1e-13); // ~1800 daily factors: roundoff only
    }

    @Test
    void matchesOisSwapWithoutLagOrLookback() {
        OisSwap ois = new OisSwap(SPOT, SPOT.plusYears(5), 0.035, 1, DayCount.ACT_360, CAL, MF);
        VanillaSwap v = new VanillaSwap(new FixedLeg(SPOT, SPOT.plusYears(5), 0.035, ANNUAL),
                leg(SPOT, SPOT.plusYears(5), ANNUAL, OvernightConvention.IN_ARREARS));
        assertEquals(ois.parRate(OIS), v.parRate(OIS, OIS), 1e-14);
        assertEquals(ois.payerPv(OIS), v.payerPv(OIS, OIS), 1e-14);
        assertEquals(ois.annuity(OIS), v.annuity(OIS), 1e-15);
        assertEquals(-v.payerPv(OIS, OIS), v.receiverPv(OIS, OIS), 0);
    }

    @Test
    void termLegMatchesInterestRateSwap() {
        CurvePair c = MultiCurveRisk.buildCurves(VAL, MultiCurveTest.oisInstruments(),
                MultiCurveTest.projectionInstruments(), CurveInterpolation.LOG_LINEAR_DISCOUNT);
        InterestRateSwap irs = MultiCurveTest.irs(5, 0.036);
        VanillaSwap v = new VanillaSwap(
                new FixedLeg(SPOT, SPOT.plusYears(5), 0.036, LegConvention.of(1, DayCount.THIRTY_360, CAL, MF)),
                new TermRateLeg(SPOT, SPOT.plusYears(5), 0, LegConvention.of(4, DayCount.ACT_360, CAL, MF), 0,
                        FixingHistory.empty()));
        assertEquals(irs.floatLegPv(c.discount(), c.projection()), v.floatLegPv(c.discount(), c.projection()),
                1e-14);
        assertEquals(irs.payerPv(c.discount(), c.projection()), v.payerPv(c.discount(), c.projection()), 1e-14);
    }

    @Test
    void parRateZeroesPv() {
        LegConvention conv = ANNUAL.withPaymentLag(2);
        OvernightLeg fl = leg(SPOT, SPOT.plusYears(10), conv, new OvernightConvention(2, true, 0));
        VanillaSwap v = new VanillaSwap(new FixedLeg(SPOT, SPOT.plusYears(10), 0.03, conv), fl);
        double par = v.parRate(OIS, OIS);
        VanillaSwap atPar = new VanillaSwap(v.fixed().withRate(par), fl);
        assertEquals(0, atPar.payerPv(OIS, OIS), 1e-15);
    }

    @Test
    void paymentLagDiscountsLater() {
        OvernightLeg noLag = leg(SPOT, SPOT.plusYears(3), ANNUAL, OvernightConvention.IN_ARREARS);
        OvernightLeg lag = leg(SPOT, SPOT.plusYears(3), ANNUAL.withPaymentLag(2), OvernightConvention.IN_ARREARS);
        assertTrue(lag.pv(OIS, OIS) < noLag.pv(OIS, OIS));
        LegConvention.Period p = lag.periods().getFirst();
        assertEquals(CAL.addBusinessDays(p.accrualEnd(), 2), p.paymentDate());
    }

    @Test
    void stubsFollowTheConvention() {
        LocalDate end = LocalDate.of(2029, 8, 15);
        List<LegConvention.Period> front = ANNUAL.periods(SPOT, end);
        List<LegConvention.Period> back = ANNUAL.withStub(StubType.SHORT_BACK).periods(SPOT, end);
        assertEquals(3, front.size());
        assertEquals(LocalDate.of(2027, 8, 16), front.getFirst().accrualEnd()); // 15 Aug 2027 is a Sunday
        assertEquals(LocalDate.of(2027, 11, 4), back.getFirst().accrualEnd());
        assertTrue(front.getFirst().yearFraction() < 1);
    }

    @Test
    void lookbackShiftAndLockoutChangeTheCouponOnASlopedCurve() {
        // A one-month period ending just after the 2027-02-04 pillar, where the
        // log-linear forward jumps, so moving the last fixings changes the coupon.
        LocalDate s = LocalDate.of(2027, 1, 5);
        LocalDate e = LocalDate.of(2027, 2, 5);
        LegConvention monthly = LegConvention.of(12, DayCount.ACT_360, CAL, MF);
        LegConvention.Period p = monthly.periods(s, e).getFirst();
        double plain = leg(s, e, monthly, OvernightConvention.IN_ARREARS).couponRate(p, OIS);
        double lb = leg(s, e, monthly, new OvernightConvention(2, false, 0)).couponRate(p, OIS);
        double sh = leg(s, e, monthly, new OvernightConvention(2, true, 0)).couponRate(p, OIS);
        double lo = leg(s, e, monthly, new OvernightConvention(0, false, 2)).couponRate(p, OIS);
        for (double r : new double[] {lb, sh, lo}) {
            assertNotEquals(plain, r, 1e-9);
            assertEquals(plain, r, 5e-4);
        }
    }

    @Test
    void seasonedLegUsesFixingsAndRejectsMissingOnes() {
        LocalDate start = LocalDate.of(2026, 8, 3);
        LocalDate end = LocalDate.of(2027, 8, 3);
        OvernightLeg missing = leg(start, end, ANNUAL, OvernightConvention.IN_ARREARS);
        assertThrows(IllegalStateException.class, () -> missing.pv(OIS, OIS));
        java.util.TreeMap<LocalDate, Double> fx = new java.util.TreeMap<>();
        for (LocalDate d = start.minusDays(10); d.isBefore(VAL); d = CAL.addBusinessDays(d, 1)) {
            fx.put(d, 0.05);
        }
        FixingHistory h = FixingHistory.of(fx);
        assertEquals(fx.size(), h.size());
        assertEquals(0.05, h.fixing(start).orElseThrow(), 0);
        assertTrue(h.fixing(VAL).isEmpty());
        OvernightLeg seasoned = new OvernightLeg(start, end, 0.001, ANNUAL, OvernightConvention.IN_ARREARS, h);
        LegConvention.Period p = seasoned.periods().getFirst();
        // Past days at 5% raise the coupon above the forward-only compounding from today.
        double fwdOnly = (OIS.discountFactor(VAL) / OIS.discountFactor(p.accrualEnd()) - 1)
                / DayCount.ACT_360.yearFraction(VAL, p.accrualEnd());
        assertTrue(seasoned.couponRate(p, OIS) > fwdOnly + 0.001);
        TermRateLeg term = new TermRateLeg(start, end, 0, LegConvention.of(4, DayCount.ACT_360, CAL, MF), 2, h);
        assertEquals(0.05, term.couponRate(term.periods().getFirst(), OIS), 0);
        assertThrows(IllegalStateException.class, () -> new TermRateLeg(start, end, 0,
                LegConvention.of(4, DayCount.ACT_360, CAL, MF), 2, FixingHistory.empty()).pv(OIS, OIS));
    }

    @Test
    void paidCouponsAreExcluded() {
        LocalDate start = LocalDate.of(2024, 11, 4);
        FixedLeg f = new FixedLeg(start, LocalDate.of(2028, 11, 6), 0.04, ANNUAL);
        double a = 0;
        for (LegConvention.Period p : f.periods()) {
            if (p.paymentDate().isAfter(VAL)) {
                a += p.yearFraction() * OIS.discountFactor(p.paymentDate());
            }
        }
        assertEquals(a, f.annuity(OIS), 0);
        assertEquals(0.04 * a, f.pv(OIS), 1e-16);
    }

    @Test
    void validation() {
        assertThrows(IllegalArgumentException.class, () -> LegConvention.of(5, DayCount.ACT_360, CAL, MF));
        assertThrows(IllegalArgumentException.class, () -> ANNUAL.withPaymentLag(-1));
        assertThrows(IllegalArgumentException.class, () -> new OvernightConvention(-1, false, 0));
        assertThrows(IllegalArgumentException.class, () -> new OvernightConvention(0, false, -1));
        assertThrows(IllegalArgumentException.class, () -> FixingHistory.of(Map.of(VAL, Double.NaN)));
        assertThrows(IllegalArgumentException.class, () -> new TermRateLeg(SPOT, SPOT.plusYears(1), 0,
                ANNUAL, -1, FixingHistory.empty()));
        assertThrows(IllegalArgumentException.class, () -> new FixedLeg(SPOT, SPOT, 0.01, ANNUAL));
        assertThrows(IllegalArgumentException.class, () -> new FixedLeg(SPOT, SPOT.plusYears(1), Double.NaN,
                ANNUAL));
        OvernightLeg shortLock = leg(SPOT, SPOT.plusDays(3), LegConvention.of(12, DayCount.ACT_360, CAL, MF),
                new OvernightConvention(0, false, 5));
        assertThrows(IllegalArgumentException.class, () -> shortLock.pv(OIS, OIS));
        assertThrows(IllegalArgumentException.class,
                () -> LegConvention.of(1, DayCount.ACT_360, CAL, Roll.FOLLOWING).periods(
                        LocalDate.of(2026, 11, 7), LocalDate.of(2026, 11, 8)));
        OvernightLeg weekend = leg(LocalDate.of(2026, 11, 7), LocalDate.of(2026, 11, 8),
                LegConvention.of(1, DayCount.ACT_360, CAL, Roll.NONE), OvernightConvention.IN_ARREARS);
        assertThrows(IllegalArgumentException.class, () -> weekend.pv(OIS, OIS));
    }
}
