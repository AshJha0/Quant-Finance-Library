package com.quantfinlib.rates;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Day-count conventions: the year fraction between two dates as real term
 * sheets define it — because finance never agreed on how long a year is,
 * and the disagreement is worth real money.
 *
 * <p>An interest payment is {@code notional × rate × yearFraction}, and the
 * SAME two calendar dates produce different year fractions under different
 * conventions: 2024-01-15 → 2024-07-15 is 182 days, which is 0.5056 under
 * ACT/360 (money markets: USD deposits, SOFR), 0.4986 under ACT/365
 * (GBP money markets, many swap fixed legs), and exactly 0.5 under 30/360
 * (US corporate bonds, which pretend every month has 30 days so coupons
 * come out round). On a $100m swap leg at 5%, picking the wrong convention
 * moves the payment by tens of thousands of dollars — a booking error that
 * surfaces as an unexplained break on settlement day, not a model error.</p>
 *
 * <p>The rule in practice: the convention is part of the INSTRUMENT (read
 * the term sheet), never a system-wide default. That is why
 * {@link BondPricer} and the curve utilities take a {@code DayCount}
 * argument instead of assuming one. 30/360's end-of-month adjustments
 * (the 31st treated as the 30th when the start is on the 30th) are the US
 * Bond Basis variant; other markets use slightly different 30/360 flavors
 * — stated here so nobody "fixes" the adjustment against ISDA 30E/360.</p>
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
}
