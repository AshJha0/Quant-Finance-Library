package com.quantfinlib.rates;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The schedule and accrual conventions of one swap leg: how often it pays,
 * how a period's year fraction is measured, which calendar and roll adjust
 * the dates, where a stub goes, and how many business days after the accrual
 * end the payment is made.
 *
 * <p>The plain idea: two swaps with the same dates and rates can differ in
 * value only through these fields, so they are stated once per leg instead
 * of being assumed (the {@code SwapPricer} methods on a
 * {@link YieldCurve}, removed in 4.0, assumed an annual fixed leg with {@code tau = 1}).</p>
 *
 * <p>Formally, the unadjusted period ends come from
 * {@link BusinessCalendar#unadjustedSchedule(LocalDate, LocalDate, int, BusinessCalendar.StubType, boolean)};
 * accrual dates are those dates rolled with {@code roll} (the start
 * included); the payment date of a period ending at {@code e} is
 * {@code calendar.addBusinessDays(e, paymentLagDays)}. A SOFR OIS under the
 * ISDA 2021 / ARRC convention is {@code (1, ACT_360, US calendar,
 * MODIFIED_FOLLOWING, SHORT_FRONT, false, 2)}.</p>
 *
 * @param paymentsPerYear payments per year, a divisor of 12
 * @param dayCount        accrual day count of the leg
 * @param calendar        business-day calendar for rolls and the payment lag
 * @param roll            roll convention for accrual dates
 * @param stub            where an irregular period goes
 * @param endOfMonth      end-of-month rule for the unadjusted schedule
 * @param paymentLagDays  business days from accrual end to payment, {@code >= 0}
 */
public record LegConvention(int paymentsPerYear, DayCount dayCount, BusinessCalendar calendar,
                            BusinessCalendar.Roll roll, BusinessCalendar.StubType stub,
                            boolean endOfMonth, int paymentLagDays) {

    /** Validates the fields. */
    public LegConvention {
        if (paymentsPerYear < 1 || 12 % paymentsPerYear != 0) {
            throw new IllegalArgumentException("paymentsPerYear must divide 12, got " + paymentsPerYear);
        }
        Objects.requireNonNull(dayCount, "dayCount");
        Objects.requireNonNull(calendar, "calendar");
        Objects.requireNonNull(roll, "roll");
        Objects.requireNonNull(stub, "stub");
        if (paymentLagDays < 0) {
            throw new IllegalArgumentException("paymentLagDays must be >= 0, got " + paymentLagDays);
        }
    }

    /**
     * A convention with a short front stub, no end-of-month rule and no payment lag.
     *
     * @param paymentsPerYear payments per year, a divisor of 12
     * @param dayCount        accrual day count
     * @param calendar        business-day calendar
     * @param roll            roll convention
     * @return the convention
     */
    public static LegConvention of(int paymentsPerYear, DayCount dayCount, BusinessCalendar calendar,
                                   BusinessCalendar.Roll roll) {
        return new LegConvention(paymentsPerYear, dayCount, calendar, roll,
                BusinessCalendar.StubType.SHORT_FRONT, false, 0);
    }

    /**
     * The same convention with a different payment lag.
     *
     * @param days business days from accrual end to payment
     * @return the new convention
     */
    public LegConvention withPaymentLag(int days) {
        return new LegConvention(paymentsPerYear, dayCount, calendar, roll, stub, endOfMonth, days);
    }

    /**
     * The same convention with a different stub type.
     *
     * @param stubType where the irregular period goes
     * @return the new convention
     */
    public LegConvention withStub(BusinessCalendar.StubType stubType) {
        return new LegConvention(paymentsPerYear, dayCount, calendar, roll, stubType, endOfMonth,
                paymentLagDays);
    }

    /**
     * The accrual periods of a leg from {@code start} to {@code end}
     * (unadjusted dates), ascending, with adjusted accrual dates and payment dates.
     *
     * @param start unadjusted effective date
     * @param end   unadjusted maturity date
     * @return the periods, at least one
     */
    public List<Period> periods(LocalDate start, LocalDate end) {
        Legs.requirePeriod(start, end);
        List<LocalDate> ends = BusinessCalendar.unadjustedSchedule(start, end, paymentsPerYear, stub,
                endOfMonth);
        List<Period> out = new ArrayList<>(ends.size());
        LocalDate adjustedEnd = calendar.roll(end, roll);
        LocalDate prev = calendar.roll(start, roll);
        for (LocalDate u : ends) {
            LocalDate e = calendar.roll(u, roll);
            if (!e.isAfter(prev)) {
                throw new IllegalArgumentException("empty accrual period ending " + u);
            }
            out.add(new Period(prev, e, calendar.addBusinessDays(e, paymentLagDays),
                    dayCount.yearFraction(prev, e, adjustedEnd)));
            prev = e;
        }
        return List.copyOf(out);
    }

    /**
     * One accrual period.
     *
     * @param accrualStart adjusted accrual start
     * @param accrualEnd   adjusted accrual end
     * @param paymentDate  payment date (accrual end plus the payment lag)
     * @param yearFraction accrual year fraction under the leg day count
     */
    public record Period(LocalDate accrualStart, LocalDate accrualEnd, LocalDate paymentDate,
                         double yearFraction) {
    }
}
