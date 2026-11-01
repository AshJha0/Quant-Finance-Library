package com.quantfinlib.rates;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Day-count conventions: the year fraction between two dates as real term
 * sheets define it — because finance never agreed on how long a year is,
 * and the disagreement is worth real money.
 *
 * <p>An interest payment is {@code notional × rate × yearFraction}, and the
 * same two calendar dates produce different year fractions under different
 * conventions: 2024-01-15 → 2024-07-15 is 182 days, which is 0.5056 under
 * ACT/360 (money markets: USD deposits, SOFR), 0.4986 under ACT/365
 * (GBP money markets, many swap fixed legs), and exactly 0.5 under 30/360
 * (US corporate bonds, which pretend every month has 30 days so coupons
 * come out round). On a $100m swap leg at 5%, picking the wrong convention
 * moves the payment by tens of thousands of dollars — a booking error that
 * surfaces as an unexplained break on settlement day, not a model error.</p>
 *
 * <p>The rule in practice: the convention is part of the instrument (read
 * the term sheet), never a system-wide default. That is why
 * {@link BondPricer} and the curve utilities take a {@code DayCount}
 * argument instead of assuming one.</p>
 *
 * <p>Definitions follow the 2006 ISDA Definitions, section 4.16:
 * {@link #THIRTY_360} is 30/360 Bond Basis (4.16(f)), {@link #THIRTY_E_360}
 * is 30E/360 Eurobond Basis (4.16(g)), {@link #THIRTY_E_360_ISDA} is
 * 30E/360 (ISDA) (4.16(h)), {@link #ACT_ACT_ISDA} is 4.16(b).
 * {@link #ACT_ACT_ICMA} (ICMA Rule 251, 4.16(c)) depends on the coupon period
 * and frequency as well as the two dates: {@link BondPricer} handles it, and
 * {@link #actActIcma(LocalDate, LocalDate, LocalDate, LocalDate, int)} computes
 * it directly. BUS/252 (Brazil) needs a holiday calendar and is
 * {@link #bus252(BusinessCalendar, LocalDate, LocalDate)}.</p>
 */
public enum DayCount {

    /** Actual days / 360 — money markets (USD LIBOR/SOFR style). */
    ACT_360 {
        @Override
        public double yearFraction(LocalDate start, LocalDate end) {
            return ChronoUnit.DAYS.between(start, end) / 360.0;
        }
    },

    /** Actual days / 365 (fixed) — GBP money markets, many swaps. */
    ACT_365 {
        @Override
        public double yearFraction(LocalDate start, LocalDate end) {
            return ChronoUnit.DAYS.between(start, end) / 365.0;
        }
    },

    /** US (Bond Basis) 30/360 — corporate and agency bonds. */
    THIRTY_360 {
        @Override
        public double yearFraction(LocalDate start, LocalDate end) {
            int d1 = Math.min(start.getDayOfMonth(), 30);
            int d2 = end.getDayOfMonth();
            if (d2 == 31 && d1 == 30) {
                d2 = 30;
            }
            int days = 360 * (end.getYear() - start.getYear())
                    + 30 * (end.getMonthValue() - start.getMonthValue())
                    + (d2 - d1);
            return days / 360.0;
        }
    },

    /**
     * 30E/360 (Eurobond Basis): both day numbers capped at 30, with no
     * condition on the other date.
     */
    THIRTY_E_360 {
        @Override
        public double yearFraction(LocalDate start, LocalDate end) {
            return thirty360(start, end, Math.min(start.getDayOfMonth(), 30),
                    Math.min(end.getDayOfMonth(), 30));
        }
    },

    /**
     * 30E/360 (ISDA): a date on the last day of its month counts as the
     * 30th, except that the termination date is left alone when it is the
     * last day of February. The two-date form treats {@code end} as not
     * being the termination date; use
     * {@link #yearFraction(LocalDate, LocalDate, LocalDate)} for the final period.
     */
    THIRTY_E_360_ISDA {
        @Override
        public double yearFraction(LocalDate start, LocalDate end) {
            return yearFraction(start, end, null);
        }

        @Override
        public double yearFraction(LocalDate start, LocalDate end, LocalDate terminationDate) {
            int d1 = isLastDayOfMonth(start) ? 30 : start.getDayOfMonth();
            int d2 = end.getDayOfMonth();
            boolean febTermination = end.equals(terminationDate) && end.getMonthValue() == 2;
            if (isLastDayOfMonth(end) && !febTermination) {
                d2 = 30;
            }
            return thirty360(start, end, d1, d2);
        }
    },

    /**
     * ACT/ACT ICMA — government bonds (US Treasuries, gilts, Bunds). Needs the
     * coupon period, so the two-date form throws; use
     * {@link #actActIcma(LocalDate, LocalDate, LocalDate, LocalDate, int)} or
     * pass this constant to {@link BondPricer}.
     */
    ACT_ACT_ICMA {
        @Override
        public double yearFraction(LocalDate start, LocalDate end) {
            throw new UnsupportedOperationException(
                    "ACT/ACT ICMA needs the coupon period: use DayCount.actActIcma");
        }
    },

    /** ACT/ACT ISDA — government bonds and ISDA swap legs; leap-year exact. */
    ACT_ACT_ISDA {
        @Override
        public double yearFraction(LocalDate start, LocalDate end) {
            if (start.getYear() == end.getYear()) {
                return ChronoUnit.DAYS.between(start, end) / daysInYear(start.getYear());
            }
            double first = ChronoUnit.DAYS.between(start, LocalDate.of(start.getYear() + 1, 1, 1))
                    / daysInYear(start.getYear());
            double last = ChronoUnit.DAYS.between(LocalDate.of(end.getYear(), 1, 1), end)
                    / daysInYear(end.getYear());
            return first + (end.getYear() - start.getYear() - 1) + last;
        }

        private static double daysInYear(int year) {
            return LocalDate.of(year, 1, 1).isLeapYear() ? 366.0 : 365.0;
        }
    };

    /** Year fraction from {@code start} (inclusive) to {@code end} (exclusive). */
    public abstract double yearFraction(LocalDate start, LocalDate end);

    /**
     * Year fraction when the convention depends on whether {@code end} is the
     * termination (maturity) date. Only {@link #THIRTY_E_360_ISDA} uses it.
     */
    public double yearFraction(LocalDate start, LocalDate end, LocalDate terminationDate) {
        return yearFraction(start, end);
    }

    /**
     * ACT/ACT ICMA (ICMA Rule 251): days(start, end) / (frequency ×
     * days(periodStart, periodEnd)), where [periodStart, periodEnd) is the
     * regular (quasi-)coupon period containing [start, end). A full regular
     * coupon period therefore has fraction exactly 1/frequency.
     */
    public static double actActIcma(LocalDate start, LocalDate end, LocalDate periodStart,
                                    LocalDate periodEnd, int frequency) {
        long periodDays = ChronoUnit.DAYS.between(periodStart, periodEnd);
        if (periodDays <= 0 || frequency < 1) {
            throw new IllegalArgumentException("need a positive reference period and frequency");
        }
        return ChronoUnit.DAYS.between(start, end) / (double) (frequency * periodDays);
    }

    /**
     * BUS/252: business days d with start &lt;= d &lt; end on {@code calendar},
     * divided by 252 (Brazilian DI and government bond convention, ANBIMA).
     */
    public static double bus252(BusinessCalendar calendar, LocalDate start, LocalDate end) {
        if (end.isBefore(start)) {
            return -bus252(calendar, end, start);
        }
        long n = 0;
        for (LocalDate d = start; d.isBefore(end); d = d.plusDays(1)) {
            if (calendar.isBusinessDay(d)) {
                n++;
            }
        }
        return n / 252.0;
    }

    private static boolean isLastDayOfMonth(LocalDate d) {
        return d.getDayOfMonth() == d.lengthOfMonth();
    }

    private static double thirty360(LocalDate start, LocalDate end, int d1, int d2) {
        int days = 360 * (end.getYear() - start.getYear())
                + 30 * (end.getMonthValue() - start.getMonthValue())
                + (d2 - d1);
        return days / 360.0;
    }
}
