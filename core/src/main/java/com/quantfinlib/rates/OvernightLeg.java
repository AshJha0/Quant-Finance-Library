package com.quantfinlib.rates;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A compounded-in-arrears overnight floating leg (SOFR, ESTR, SONIA) with
 * payment lag, lookback, observation shift, lockout, stubs and realised
 * fixings for seasoned trades.
 *
 * <p>The plain idea: each coupon is the daily-compounded overnight rate over
 * its period plus a spread. Days before the valuation date use the published
 * fixings in {@link FixingHistory}; later days are forecast as the one-day
 * simple forward of the projection curve, {@code r(f) = (P(f)/P(f+1bd) - 1)/alpha}.</p>
 *
 * <p>Formal statement (ISDA 2021 section 6.9; {@link OvernightConvention}
 * gives the lookback L, shift flag and lockout k): with business days
 * {@code o_0 < ... < o_n} of the observation window ({@code [s, e]}, or
 * {@code [s - L, e - L]} in business days under a shift), and
 * {@code alpha_i = dayCount.yearFraction(o_i, o_{i+1})},</p>
 * <pre>
 *   R = ( prod_{i&lt;n} (1 + r_i alpha_i) - 1 ) / sum_{i&lt;n} alpha_i
 * </pre>
 * <p>where {@code r_i} is the fixing for {@code o_i} under a shift, or for
 * {@code o_i - L} business days without one (the weights then stay on the
 * accrual days); the last k rates are replaced by {@code r_{n-k-1}}. The
 * coupon {@code (R + spread) * tau} is paid on the period's payment date.</p>
 *
 * <p>Worked example: with {@link OvernightConvention#IN_ARREARS}, no
 * spread, no payment lag and projection = discount curve, the leg PV
 * telescopes to {@code P(start) - P(end)}, the value {@link OisSwap} uses; the
 * tests pin that to 1e-13 (roundoff over the daily product) and cross-check the conventions against QuantLib
 * output (golden file {@code golden/quantlib-sofr.csv}).</p>
 *
 * @param start      unadjusted effective date
 * @param end        unadjusted maturity
 * @param spread     spread added to the compounded rate (simple, not compounded)
 * @param convention schedule, day count, calendar and payment lag
 * @param overnight  lookback, shift and lockout
 * @param fixings    published fixings of the index
 */
public record OvernightLeg(LocalDate start, LocalDate end, double spread, LegConvention convention,
                           OvernightConvention overnight, FixingHistory fixings) implements FloatLeg {

    /** Validates the fields. */
    public OvernightLeg {
        Legs.requirePeriod(start, end);
        Legs.requireFinite(spread, "spread");
        Objects.requireNonNull(convention, "convention");
        Objects.requireNonNull(overnight, "overnight");
        Objects.requireNonNull(fixings, "fixings");
        convention.periods(start, end);
    }

    @Override
    public List<LegConvention.Period> periods() {
        return convention.periods(start, end);
    }

    @Override
    public double couponRate(LegConvention.Period period, DiscountCurve projection) {
        return compoundedRate(period.accrualStart(), period.accrualEnd(), projection) + spread;
    }

    /**
     * The compounded overnight rate (no spread) for an accrual period.
     *
     * @param accrualStart adjusted accrual start
     * @param accrualEnd   adjusted accrual end
     * @param projection   curve forecasting fixings on or after its valuation date
     * @return the annualised compounded rate
     */
    public double compoundedRate(LocalDate accrualStart, LocalDate accrualEnd, DiscountCurve projection) {
        BusinessCalendar cal = convention.calendar();
        DayCount dc = convention.dayCount();
        int lb = overnight.lookbackDays();
        boolean shift = overnight.observationShift();
        LocalDate from = shift ? cal.subtractBusinessDays(cal.roll(accrualStart, BusinessCalendar.Roll.FOLLOWING), lb)
                : cal.roll(accrualStart, BusinessCalendar.Roll.FOLLOWING);
        LocalDate to = shift ? cal.subtractBusinessDays(cal.roll(accrualEnd, BusinessCalendar.Roll.FOLLOWING), lb)
                : accrualEnd;
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = from; d.isBefore(to); d = cal.addBusinessDays(d, 1)) {
            days.add(d);
        }
        int n = days.size();
        if (n == 0) {
            throw new IllegalArgumentException("no business day in " + accrualStart + " .. " + accrualEnd);
        }
        int k = overnight.lockoutDays();
        if (k >= n) {
            throw new IllegalArgumentException("lockout " + k + " needs more than " + n
                    + " business days in the period");
        }
        double[] r = new double[n];
        double[] alpha = new double[n];
        for (int i = 0; i < n; i++) {
            LocalDate d = days.get(i);
            LocalDate next = i + 1 < n ? days.get(i + 1) : to;
            alpha[i] = dc.yearFraction(d, next);
            LocalDate fixingDate = shift ? d : cal.subtractBusinessDays(d, lb);
            r[i] = i >= n - k ? r[n - k - 1] : fixing(fixingDate, projection);
        }
        double growth = 1;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            growth *= 1 + r[i] * alpha[i];
            sum += alpha[i];
        }
        return (growth - 1) / sum;
    }

    private double fixing(LocalDate fixingDate, DiscountCurve projection) {
        BusinessCalendar cal = convention.calendar();
        return fixings.rate(fixingDate, projection.valuationDate(),
                () -> projection.forwardRate(fixingDate, cal.addBusinessDays(fixingDate, 1),
                        convention.dayCount()));
    }
}
