package com.quantfinlib.rates;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                DayCount.THIRTY_360, BusinessCalendar.weekendsOnly(), BusinessCalendar.Roll.NONE);
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
                DayCount.THIRTY_360, BusinessCalendar.weekendsOnly(), BusinessCalendar.Roll.NONE);
        double clean = BondPricer.cleanPrice(100, 0.06, 2, settlement, maturity, 0.06,
                DayCount.THIRTY_360, BusinessCalendar.weekendsOnly(), BusinessCalendar.Roll.NONE);
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
}
