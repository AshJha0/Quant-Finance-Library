package com.quantfinlib.rates;

import com.quantfinlib.util.Immutable;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Business-day calendar: weekends plus a holiday set, with the standard roll
 * conventions, settlement-lag arithmetic, and coupon schedule generation.
 *
 * <p>Why this is a finance class and not a util: cash cannot move on a day
 * the payment system is closed, so every real-world date in a trade — the
 * settlement date (spot = trade + 2 business days in most FX pairs, T+1 US
 * equities), each coupon date, an option expiry — must land on a business
 * day, and which way a weekend date rolls changes the accrual period and
 * therefore the payment ({@link DayCount} then converts the rolled dates
 * to a year fraction). {@code MODIFIED_FOLLOWING} — roll forward unless
 * that crosses month-end, then backward — is the near-universal swap
 * convention precisely because plain following would occasionally push a
 * month's payment into the next month and break month-bucketed accounting.
 * A holiday missing from the set silently shifts settlement by a day: the
 * classic source of small unexplained cash breaks between two systems that
 * disagree about, say, Whit Monday.</p>
 */
@Immutable
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

    /**
     * Where an irregular (stub) period goes when the dates do not divide
     * into whole periods (ISDA 2006 definitions, section 4.14 and market
     * practice). Front stubs generate backward from maturity, back stubs
     * forward from the effective date.
     */
    public enum StubType {
        /** Short first period; dates rolled back from maturity (bond default). */
        SHORT_FRONT,
        /** First period longer than regular: the short stub merged into the next period. */
        LONG_FRONT,
        /** Short last period; dates rolled forward from the effective date. */
        SHORT_BACK,
        /** Last period longer than regular. */
        LONG_BACK
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

    /**
     * The joint calendar of two trading centers: a day is a business day
     * only when it is one in both (holiday sets union). This is the FX
     * settlement rule — one calendar object, so every roll convention and
     * schedule helper works unchanged on the pair, instead of each caller
     * re-implementing dual-calendar walks.
     */
    public BusinessCalendar union(BusinessCalendar other) {
        java.util.Set<LocalDate> combined = new java.util.HashSet<>(holidays);
        combined.addAll(other.holidays);
        return new BusinessCalendar(combined);
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

    /** Walks back {@code n >= 0} business days — e.g. a fixing lag before settlement. */
    public LocalDate subtractBusinessDays(LocalDate date, int n) {
        if (n < 0) {
            throw new IllegalArgumentException("n must be >= 0");
        }
        LocalDate d = date;
        for (int i = 0; i < n; i++) {
            d = previousBusinessDay(d);
        }
        return d;
    }

    /**
     * Coupon payment schedule: unadjusted dates generated backward from
     * maturity every {@code 12/paymentsPerYear} months (short front stub, no
     * end-of-month rule), then rolled. Returns the adjusted payment dates
     * strictly after {@code effectiveDate}, ascending (last = adjusted maturity).
     */
    public List<LocalDate> schedule(LocalDate effectiveDate, LocalDate maturity,
                                    int paymentsPerYear, Roll convention) {
        return schedule(effectiveDate, maturity, paymentsPerYear, convention,
                StubType.SHORT_FRONT, false);
    }

    /** As {@link #schedule(LocalDate, LocalDate, int, Roll)} with a stub type and EOM rule. */
    public List<LocalDate> schedule(LocalDate effectiveDate, LocalDate maturity,
                                    int paymentsPerYear, Roll convention,
                                    StubType stub, boolean endOfMonth) {
        List<LocalDate> unadjusted =
                unadjustedSchedule(effectiveDate, maturity, paymentsPerYear, stub, endOfMonth);
        List<LocalDate> out = new ArrayList<>(unadjusted.size());
        for (LocalDate d : unadjusted) {
            out.add(roll(d, convention));
        }
        return out;
    }

    /** Unadjusted coupon dates strictly after {@code effectiveDate}: short front stub, no EOM rule. */
    public static List<LocalDate> unadjustedSchedule(LocalDate effectiveDate, LocalDate maturity,
                                                     int paymentsPerYear) {
        return unadjustedSchedule(effectiveDate, maturity, paymentsPerYear, StubType.SHORT_FRONT,
                false);
    }

    /**
     * Unadjusted (theoretical) period end dates strictly after
     * {@code effectiveDate}, ascending, the last one being {@code maturity}
     * (or, for back stubs, also {@code maturity}).
     *
     * <p>Every date is computed from the fixed anchor (maturity for front
     * stubs, the effective date for back stubs) as anchor ± k·months, never by
     * stepping from the previous date: {@link LocalDate#minusMonths} clamps
     * the day of month, and stepping would carry a clamp forward (31 Aug,
     * 28 Feb, then 28 Aug instead of 31 Aug).</p>
     *
     * <p>With {@code endOfMonth} and an anchor on the last day of its month,
     * every generated date is moved to the last day of its month (the EOM
     * rule used for bonds and swaps; a 28 Feb 2027 maturity then pays on
     * 31 Aug, not 28 Aug).</p>
     */
    public static List<LocalDate> unadjustedSchedule(LocalDate effectiveDate, LocalDate maturity,
                                                     int paymentsPerYear, StubType stub,
                                                     boolean endOfMonth) {
        if (paymentsPerYear < 1 || 12 % paymentsPerYear != 0) {
            throw new IllegalArgumentException("paymentsPerYear must divide 12");
        }
        if (!maturity.isAfter(effectiveDate)) {
            throw new IllegalArgumentException("maturity must be after effective date");
        }
        java.util.Objects.requireNonNull(stub, "stub");
        int months = 12 / paymentsPerYear;
        List<LocalDate> dates = new ArrayList<>();
        if (stub == StubType.SHORT_FRONT || stub == StubType.LONG_FRONT) {
            int k = 0;
            LocalDate d = maturity;
            while (d.isAfter(effectiveDate)) {
                dates.add(d);
                k++;
                d = shiftMonths(maturity, -(long) months * k, endOfMonth);
            }
            Collections.reverse(dates);
            boolean hasStub = !d.equals(effectiveDate);
            if (stub == StubType.LONG_FRONT && hasStub && dates.size() >= 2) {
                dates.removeFirst();
            }
        } else {
            int k = 1;
            LocalDate d = shiftMonths(effectiveDate, months, endOfMonth);
            while (d.isBefore(maturity)) {
                dates.add(d);
                k++;
                d = shiftMonths(effectiveDate, (long) months * k, endOfMonth);
            }
            boolean hasStub = !d.equals(maturity);
            if (stub == StubType.LONG_BACK && hasStub && !dates.isEmpty()) {
                dates.removeLast();
            }
            dates.add(maturity);
        }
        return dates;
    }

    /**
     * {@code anchor} shifted by {@code months}; with {@code endOfMonth} and an
     * anchor on its month's last day, the result is the last day of its month.
     */
    public static LocalDate shiftMonths(LocalDate anchor, long months, boolean endOfMonth) {
        LocalDate d = anchor.plusMonths(months);
        if (endOfMonth && anchor.getDayOfMonth() == anchor.lengthOfMonth()) {
            d = d.withDayOfMonth(d.lengthOfMonth());
        }
        return d;
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
