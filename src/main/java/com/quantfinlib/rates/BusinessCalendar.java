package com.quantfinlib.rates;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Business-day calendar: weekends plus a holiday set, with the standard roll
 * conventions, settlement-lag arithmetic, and coupon schedule generation.
 */
public final class BusinessCalendar {

    /** Date roll conventions for payment dates landing on non-business days. */
    public enum Roll {
        /** Leave the date as generated (for theoretical pricing). */
        NONE,
        /** Move forward to the next business day. */
        FOLLOWING,
        /** Forward, unless that crosses month-end — then backward. */
        MODIFIED_FOLLOWING,
        /** Move backward to the previous business day. */
        PRECEDING
    }

    private final Set<LocalDate> holidays;

    private BusinessCalendar(Set<LocalDate> holidays) {
        this.holidays = Set.copyOf(holidays);
    }

    public static BusinessCalendar weekendsOnly() {
        return new BusinessCalendar(Set.of());
    }

    public static BusinessCalendar withHolidays(Set<LocalDate> holidays) {
        return new BusinessCalendar(holidays);
    }

    public static BusinessCalendar withHolidays(LocalDate... holidays) {
        return new BusinessCalendar(Set.of(holidays));
    }

    public boolean isBusinessDay(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY && !holidays.contains(date);
    }

    /** Applies the roll convention to a date. */
    public LocalDate roll(LocalDate date, Roll convention) {
        if (convention == Roll.NONE || isBusinessDay(date)) {
            return date;
        }
        return switch (convention) {
            case FOLLOWING -> nextBusinessDay(date);
            case PRECEDING -> previousBusinessDay(date);
            case MODIFIED_FOLLOWING -> {
                LocalDate following = nextBusinessDay(date);
                yield following.getMonth() == date.getMonth() ? following : previousBusinessDay(date);
            }
            case NONE -> date;
        };
    }

    /** Adds {@code n >= 0} business days — e.g. T+2 settlement from a trade date. */
    public LocalDate addBusinessDays(LocalDate date, int n) {
        if (n < 0) {
            throw new IllegalArgumentException("n must be >= 0");
        }
        LocalDate d = date;
        for (int i = 0; i < n; i++) {
            d = nextBusinessDay(d);
        }
        return d;
    }

    /**
     * Coupon payment schedule: unadjusted dates generated backward from
     * maturity every {@code 12/paymentsPerYear} months, then rolled. Returns
     * the adjusted payment dates strictly after {@code effectiveDate},
     * ascending (last = adjusted maturity).
     */
    public List<LocalDate> schedule(LocalDate effectiveDate, LocalDate maturity,
                                    int paymentsPerYear, Roll convention) {
        List<LocalDate> unadjusted = unadjustedSchedule(effectiveDate, maturity, paymentsPerYear);
        List<LocalDate> out = new ArrayList<>(unadjusted.size());
        for (LocalDate d : unadjusted) {
            out.add(roll(d, convention));
        }
        return out;
    }

    /** The unadjusted (theoretical) coupon dates strictly after {@code effectiveDate}. */
    public static List<LocalDate> unadjustedSchedule(LocalDate effectiveDate, LocalDate maturity,
                                                     int paymentsPerYear) {
        if (paymentsPerYear < 1 || 12 % paymentsPerYear != 0) {
            throw new IllegalArgumentException("paymentsPerYear must divide 12");
        }
        if (!maturity.isAfter(effectiveDate)) {
            throw new IllegalArgumentException("maturity must be after effective date");
        }
        int months = 12 / paymentsPerYear;
        List<LocalDate> dates = new ArrayList<>();
        LocalDate d = maturity;
        while (d.isAfter(effectiveDate)) {
            dates.add(d);
            d = d.minusMonths(months);
        }
        Collections.reverse(dates);
        return dates;
    }

    private LocalDate nextBusinessDay(LocalDate date) {
        LocalDate d = date.plusDays(1);
        while (!isBusinessDay(d)) {
            d = d.plusDays(1);
        }
        return d;
    }

    private LocalDate previousBusinessDay(LocalDate date) {
        LocalDate d = date.minusDays(1);
        while (!isBusinessDay(d)) {
            d = d.minusDays(1);
        }
        return d;
    }
}
