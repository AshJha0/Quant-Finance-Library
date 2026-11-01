package com.quantfinlib.rates;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConventionsTest {

    @Test
    void dayCountConventions() {
        LocalDate jan15 = LocalDate.of(2026, 1, 15);
        LocalDate apr15 = LocalDate.of(2026, 4, 15);
        LocalDate jul15 = LocalDate.of(2026, 7, 15);

        assertEquals(90 / 360.0, DayCount.ACT_360.yearFraction(jan15, apr15), 1e-12);
        assertEquals(90 / 365.0, DayCount.ACT_365.yearFraction(jan15, apr15), 1e-12);
        assertEquals(0.25, DayCount.THIRTY_360.yearFraction(jan15, apr15), 1e-12);
        assertEquals(0.5, DayCount.THIRTY_360.yearFraction(jan15, jul15), 1e-12);
        // 30/360 end-of-month rule: 30th to 31st counts as 30th.
        assertEquals(1 / 12.0, DayCount.THIRTY_360.yearFraction(
                LocalDate.of(2026, 1, 30), LocalDate.of(2026, 2, 28)) + 2 / 360.0, 1e-12);

        // ACT/ACT ISDA: leap year 2024 counts 366 exactly.
        assertEquals(1.0, DayCount.ACT_ACT_ISDA.yearFraction(
                LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1)), 1e-12);
        // Spanning leap and non-leap years splits by calendar year.
        double spanning = DayCount.ACT_ACT_ISDA.yearFraction(
                LocalDate.of(2024, 7, 1), LocalDate.of(2025, 7, 1));
        assertEquals(184 / 366.0 + 181 / 365.0, spanning, 1e-12);
    }

    @Test
    void businessCalendarRollsAndSettlement() {
        // 2026-07-04 is a Saturday; declare 2026-07-06 (Monday) a holiday too.
        BusinessCalendar cal = BusinessCalendar.withHolidays(LocalDate.of(2026, 7, 6));
        LocalDate saturday = LocalDate.of(2026, 7, 4);

        assertFalse(cal.isBusinessDay(saturday));
        assertFalse(cal.isBusinessDay(LocalDate.of(2026, 7, 6)));   // holiday
        assertEquals(LocalDate.of(2026, 7, 7), cal.roll(saturday, BusinessCalendar.Roll.FOLLOWING));
        assertEquals(LocalDate.of(2026, 7, 3), cal.roll(saturday, BusinessCalendar.Roll.PRECEDING));
        assertEquals(saturday, cal.roll(saturday, BusinessCalendar.Roll.NONE));

        // Modified following at month-end: Sat 2026-05-30 -> following is Jun 1
        // (month change) -> falls back to Fri May 29.
        BusinessCalendar weekends = BusinessCalendar.weekendsOnly();
        assertEquals(LocalDate.of(2026, 5, 29),
                weekends.roll(LocalDate.of(2026, 5, 30), BusinessCalendar.Roll.MODIFIED_FOLLOWING));

        // T+2 from Thursday skips the weekend and the Monday holiday.
        assertEquals(LocalDate.of(2026, 7, 7),
                cal.addBusinessDays(LocalDate.of(2026, 7, 2), 2));
    }

    @Test
    void scheduleGeneratesSemiAnnualCoupons() {
        BusinessCalendar cal = BusinessCalendar.weekendsOnly();
        List<LocalDate> dates = cal.schedule(LocalDate.of(2026, 1, 15),
                LocalDate.of(2028, 1, 15), 2, BusinessCalendar.Roll.FOLLOWING);
        assertEquals(4, dates.size());
        // 2026-07-15 is a Wednesday: unrolled.
        assertEquals(LocalDate.of(2026, 7, 15), dates.getFirst());
        // 2027-01-15 is a Friday; 2027-07-15 Thursday; maturity 2028-01-15 is a
        // Saturday -> rolled to Monday the 17th.
        assertEquals(LocalDate.of(2028, 1, 17), dates.getLast());
    }

    @Test
    void datedPricingMatchesPeriodPricingUnderCleanConventions() {
        // 30/360, unrolled dates, settlement exactly on a coupon date: the dated
        // machinery must reproduce the whole-period formula exactly.
        LocalDate settlement = LocalDate.of(2026, 1, 15);
        LocalDate maturity = LocalDate.of(2031, 1, 15);
        double dirty = BondPricer.dirtyPrice(100, 0.06, 2, settlement, maturity, 0.05,
                DayCount.THIRTY_360);
        assertEquals(BondPricer.priceFromYield(100, 0.06, 2, 5, 0.05), dirty, 1e-9);
        // On a coupon date there is no accrued interest.
        assertEquals(0, BondPricer.accruedInterest(100, 0.06, 2, settlement, maturity,
                DayCount.THIRTY_360), 1e-12);
    }

    @Test
    void accruedInterestAndCleanPriceMidPeriod() {
        // Settle 3 months into a semi-annual 6% period: accrued = 100*6%*0.25 = 1.5.
        LocalDate settlement = LocalDate.of(2026, 4, 15);
        LocalDate maturity = LocalDate.of(2031, 1, 15);
        double accrued = BondPricer.accruedInterest(100, 0.06, 2, settlement, maturity,
                DayCount.THIRTY_360);
        assertEquals(1.5, accrued, 1e-12);

        double dirty = BondPricer.dirtyPrice(100, 0.06, 2, settlement, maturity, 0.06,
                DayCount.THIRTY_360);
        double clean = BondPricer.cleanPrice(100, 0.06, 2, settlement, maturity, 0.06,
                DayCount.THIRTY_360);
        assertEquals(dirty - accrued, clean, 1e-12);
        // At coupon == yield the clean price stays near par.
        assertEquals(100, clean, 0.5);
    }

    @Test
    void settlementDateHonorsLagAndCalendar() {
        BusinessCalendar cal = BusinessCalendar.weekendsOnly();
        // Trade Friday 2026-07-03, T+2 -> Tuesday 2026-07-07.
        assertEquals(LocalDate.of(2026, 7, 7),
                BondPricer.settlementDate(LocalDate.of(2026, 7, 3), 2, cal));
    }

    @Test
    void unionCalendarBlocksEitherCentersHoliday() {
        // The FX settlement rule as one calendar: a holiday in either center
        // makes the day a non-business day of the union.
        BusinessCalendar london = BusinessCalendar.withHolidays(LocalDate.of(2026, 8, 31));
        BusinessCalendar newYork = BusinessCalendar.withHolidays(LocalDate.of(2026, 9, 7));
        BusinessCalendar joint = london.union(newYork);
        assertFalse(joint.isBusinessDay(LocalDate.of(2026, 8, 31))); // UK bank holiday
        assertFalse(joint.isBusinessDay(LocalDate.of(2026, 9, 7)));  // US Labor Day
        assertTrue(joint.isBusinessDay(LocalDate.of(2026, 9, 1)));   // open in both
        // All conventions work unchanged on the union.
        assertEquals(LocalDate.of(2026, 9, 1),
                joint.roll(LocalDate.of(2026, 8, 30), BusinessCalendar.Roll.FOLLOWING));
    }

    @Test
    void subtractBusinessDaysWalksBackOverHolidaysAndWeekends() {
        BusinessCalendar cal = BusinessCalendar.withHolidays(LocalDate.of(2026, 7, 6)); // Monday
        // From Wednesday 2026-07-08, 2 business days back: Tue 07-07, then
        // (skipping the Monday holiday and the weekend) Friday 07-03.
        assertEquals(LocalDate.of(2026, 7, 3),
                cal.subtractBusinessDays(LocalDate.of(2026, 7, 8), 2));
        assertEquals(LocalDate.of(2026, 7, 8),
                cal.subtractBusinessDays(LocalDate.of(2026, 7, 8), 0));
    }

    // ------------------------------------------------------- schedules (F19)

    @Test
    void monthEndScheduleDoesNotDriftToThe28th() {
        // Old code stepped minusMonths from the previous date: 2030-02-28 then
        // 2029-08-28. Every date is now taken from the maturity anchor.
        List<LocalDate> d = BusinessCalendar.unadjustedSchedule(
                LocalDate.of(2027, 1, 1), LocalDate.of(2030, 8, 31), 2);
        assertEquals(List.of(LocalDate.of(2027, 2, 28), LocalDate.of(2027, 8, 31),
                LocalDate.of(2028, 2, 29), LocalDate.of(2028, 8, 31), LocalDate.of(2029, 2, 28),
                LocalDate.of(2029, 8, 31), LocalDate.of(2030, 2, 28), LocalDate.of(2030, 8, 31)), d);
    }

    @Test
    void endOfMonthRuleMovesEveryDateToMonthEnd() {
        LocalDate mat = LocalDate.of(2027, 2, 28); // last day of February
        List<LocalDate> plain = BusinessCalendar.unadjustedSchedule(LocalDate.of(2025, 9, 1), mat, 2,
                BusinessCalendar.StubType.SHORT_FRONT, false);
        List<LocalDate> eom = BusinessCalendar.unadjustedSchedule(LocalDate.of(2025, 9, 1), mat, 2,
                BusinessCalendar.StubType.SHORT_FRONT, true);
        assertEquals(List.of(LocalDate.of(2026, 2, 28), LocalDate.of(2026, 8, 28), mat), plain);
        assertEquals(List.of(LocalDate.of(2026, 2, 28), LocalDate.of(2026, 8, 31), mat), eom);
    }

    @Test
    void stubTypes() {
        LocalDate eff = LocalDate.of(2026, 3, 10);
        LocalDate mat = LocalDate.of(2027, 6, 15);
        // Quarterly. Short front: 2026-03-15 .. 2027-06-15 back from maturity.
        assertEquals(LocalDate.of(2026, 3, 15), BusinessCalendar.unadjustedSchedule(eff, mat, 4,
                BusinessCalendar.StubType.SHORT_FRONT, false).getFirst());
        // Long front: the 5-day stub is merged into the next period.
        List<LocalDate> lf = BusinessCalendar.unadjustedSchedule(eff, mat, 4,
                BusinessCalendar.StubType.LONG_FRONT, false);
        assertEquals(LocalDate.of(2026, 6, 15), lf.getFirst());
        assertEquals(5, lf.size());
        // Short back: forward from the effective date, stub at the end.
        List<LocalDate> sb = BusinessCalendar.unadjustedSchedule(eff, mat, 4,
                BusinessCalendar.StubType.SHORT_BACK, false);
        assertEquals(List.of(LocalDate.of(2026, 6, 10), LocalDate.of(2026, 9, 10),
                LocalDate.of(2026, 12, 10), LocalDate.of(2027, 3, 10), LocalDate.of(2027, 6, 10),
                mat), sb);
        List<LocalDate> lb = BusinessCalendar.unadjustedSchedule(eff, mat, 4,
                BusinessCalendar.StubType.LONG_BACK, false);
        assertEquals(LocalDate.of(2027, 3, 10), lb.get(lb.size() - 2));
        assertEquals(mat, lb.getLast());
        // No stub when the dates divide evenly: long and short coincide.
        assertEquals(BusinessCalendar.unadjustedSchedule(LocalDate.of(2026, 6, 15), mat, 4,
                        BusinessCalendar.StubType.SHORT_FRONT, false),
                BusinessCalendar.unadjustedSchedule(LocalDate.of(2026, 6, 15), mat, 4,
                        BusinessCalendar.StubType.LONG_FRONT, false));
    }

    // ------------------------------------------------------ day counts (F20)

    @Test
    void thirtyEVariantsFollowIsda2006() {
        // 30E/360: both day numbers capped at 30 (ISDA 4.16(g)).
        assertEquals(31 / 360.0, DayCount.THIRTY_E_360.yearFraction(
                LocalDate.of(2026, 1, 31), LocalDate.of(2026, 3, 1)), 1e-15);
        assertEquals(89 / 360.0, DayCount.THIRTY_E_360.yearFraction(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31)), 1e-15);
        // US Bond Basis keeps the 31st when the start is not the 30th/31st.
        assertEquals(90 / 360.0, DayCount.THIRTY_360.yearFraction(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31)), 1e-15);
        // 30E/360 ISDA: end of February counts as the 30th...
        assertEquals(30 / 360.0, DayCount.THIRTY_E_360_ISDA.yearFraction(
                LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28)), 1e-15);
        // ...except on the termination date.
        assertEquals(28 / 360.0, DayCount.THIRTY_E_360_ISDA.yearFraction(
                LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28), LocalDate.of(2026, 2, 28)),
                1e-15);
        // 30E/360 (non-ISDA) leaves 28 Feb as 28.
        assertEquals(28 / 360.0, DayCount.THIRTY_E_360.yearFraction(
                LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28)), 1e-15);
    }

    @Test
    void actActIcmaAndBus252() {
        // Regular semi-annual period: exactly 1/2 whatever its length.
        assertEquals(0.5, DayCount.actActIcma(LocalDate.of(2024, 11, 15), LocalDate.of(2025, 5, 15),
                LocalDate.of(2024, 11, 15), LocalDate.of(2025, 5, 15), 2), 1e-15);
        assertEquals(122 / 362.0, DayCount.actActIcma(LocalDate.of(2024, 11, 15),
                LocalDate.of(2025, 3, 17), LocalDate.of(2024, 11, 15), LocalDate.of(2025, 5, 15), 2),
                1e-15);
        assertThrows(UnsupportedOperationException.class,
                () -> DayCount.ACT_ACT_ICMA.yearFraction(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 2, 1)));
        // BUS/252 counts business days in [start, end): Mon 2026-07-06 to Mon
        // 2026-07-13 with a holiday on Thursday the 9th is 4 days.
        BusinessCalendar cal = BusinessCalendar.withHolidays(LocalDate.of(2026, 7, 9));
        assertEquals(4 / 252.0, DayCount.bus252(cal, LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 13)),
                1e-15);
    }

    // -------------------------------------------------- street convention (F20)

    @Test
    void streetPriceMatchesPublishedExcelPriceExample() {
        // Microsoft's documented PRICE example: settlement 2008-02-15, maturity
        // 2017-11-15, coupon 5.75%, yield 6.5%, semi-annual, basis 0 (US 30/360):
        // clean price 94.63436162.
        double clean = BondPricer.cleanPrice(100, 0.0575, 2, LocalDate.of(2008, 2, 15),
                LocalDate.of(2017, 11, 15), 0.065, DayCount.THIRTY_360);
        assertEquals(94.63436162, clean, 1e-8);
        // And the documented YIELD example: maturity 2016-11-15, price
        // 95.04287 -> 6.5% (Excel shows 0.065).
        double y = BondPricer.yieldFromCleanPrice(95.04287, 100, 0.0575, 2,
                LocalDate.of(2008, 2, 15), LocalDate.of(2016, 11, 15), DayCount.THIRTY_360, false);
        assertEquals(0.065, y, 1e-6);
    }

    @Test
    void actActIcmaTreasuryPaysRoundCouponsAndAccruesOnActualPeriodDays() {
        // 4.25% semi-annual, maturity 2034-11-15, settlement 2025-03-17:
        // 122 of 181 days accrued -> 2.125 * 122/181. Price from an independent
        // implementation of the street formula (Excel PRICE, basis 1).
        LocalDate settle = LocalDate.of(2025, 3, 17);
        LocalDate mat = LocalDate.of(2034, 11, 15);
        assertEquals(2.125 * 122 / 181.0,
                BondPricer.accruedInterest(100, 0.0425, 2, settle, mat, DayCount.ACT_ACT_ICMA), 1e-13);
        assertEquals(100.4896294913431,
                BondPricer.dirtyPrice(100, 0.0425, 2, settle, mat, 0.0437, DayCount.ACT_ACT_ICMA), 1e-10);
        // On a coupon date at coupon = yield, a regular bond is at par exactly,
        // even though the two half-years have 181 and 184 days.
        assertEquals(100.0, BondPricer.dirtyPrice(100, 0.0425, 2, LocalDate.of(2024, 11, 15), mat,
                0.0425, DayCount.ACT_ACT_ICMA), 1e-10);
    }
}
