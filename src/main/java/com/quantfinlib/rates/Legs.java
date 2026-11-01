package com.quantfinlib.rates;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** Schedule and leg arithmetic shared by the curve instruments. */
final class Legs {

    private Legs() {
    }

    static void requirePeriod(LocalDate start, LocalDate end) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("end must be after start: " + start + " " + end);
        }
    }

    static void requireFinite(double v, String name) {
        if (!Double.isFinite(v)) {
            throw new IllegalArgumentException(name + " must be finite, got " + v);
        }
    }

    /** Adjusted payment dates (accrual ends) strictly after {@code start}. */
    static List<LocalDate> payments(LocalDate start, LocalDate end, int perYear,
                                    BusinessCalendar calendar, BusinessCalendar.Roll roll) {
        return calendar.schedule(start, end, perYear, roll);
    }

    /** {@code sum tau_i P_d(t_i)} over the schedule; accrual starts at the previous date. */
    static double annuity(DiscountCurve discount, LocalDate start, List<LocalDate> pay,
                          DayCount dayCount) {
        double a = 0;
        LocalDate prev = start;
        for (LocalDate d : pay) {
            a += dayCount.yearFraction(prev, d) * discount.discountFactor(d);
            prev = d;
        }
        return a;
    }

    /** {@code sum tau_j F_j P_d(t_j)}, F_j the projection forward on each accrual period. */
    static double floatLeg(DiscountCurve discount, DiscountCurve projection, LocalDate start,
                           List<LocalDate> pay, DayCount dayCount) {
        double pv = 0;
        LocalDate prev = start;
        for (LocalDate d : pay) {
            pv += dayCount.yearFraction(prev, d) * projection.forwardRate(prev, d, dayCount)
                    * discount.discountFactor(d);
            prev = d;
        }
        return pv;
    }
}
