package com.quantfinlib.rates;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * A vanilla fixed-for-floating interest rate swap in the multi-curve
 * framework: the floating coupons are projected off a projection curve
 * (for example 3M) and both legs are discounted on the OIS curve
 * (collateralised pricing; Bianchetti 2010, "Two curves, one price", Risk
 * 23(8); Ametrano and Bianchetti 2013, "Everything you always wanted to
 * know about multiple interest rate curve bootstrapping but were afraid to
 * ask", SSRN 2219548).
 *
 * <pre>
 *   annuity  A     = sum_i tau_i P_d(t_i)                       fixed leg, per unit rate
 *   float    V_flt = sum_j tau_j F_j P_d(t_j),  F_j = (P_p(t_{j-1})/P_p(t_j) - 1)/tau_j
 *   par      K_par = V_flt / A
 *   payer PV       = V_flt - K A
 * </pre>
 *
 * <p>Simplifications stated: the index fixes on the accrual period itself
 * (no fixing lag or index-tenor mismatch), payment on the accrual end date,
 * and both legs share the calendar and roll. When the projection curve is
 * the discount curve and both legs share a schedule and day count, the float
 * leg telescopes to {@code P(start) - P(end)} (the single-curve identity).</p>
 */
public record InterestRateSwap(LocalDate start, LocalDate end, double fixedRate,
                               int fixedPerYear, DayCount fixedDayCount,
                               int floatPerYear, DayCount floatDayCount,
                               BusinessCalendar calendar, BusinessCalendar.Roll roll)
        implements CurveInstrument {

    public InterestRateSwap {
        Legs.requirePeriod(start, end);
        Objects.requireNonNull(fixedDayCount, "fixedDayCount");
        Objects.requireNonNull(floatDayCount, "floatDayCount");
        Objects.requireNonNull(calendar, "calendar");
        Objects.requireNonNull(roll, "roll");
        Legs.requireFinite(fixedRate, "fixedRate");
        Legs.payments(start, end, fixedPerYear, calendar, roll);
        Legs.payments(start, end, floatPerYear, calendar, roll);
    }

    public List<LocalDate> fixedPaymentDates() {
        return Legs.payments(start, end, fixedPerYear, calendar, roll);
    }

    public List<LocalDate> floatPaymentDates() {
        return Legs.payments(start, end, floatPerYear, calendar, roll);
    }

    /** Fixed-leg annuity on the discount curve, per unit notional and unit rate. */
    public double annuity(DiscountCurve discount) {
        return Legs.annuity(discount, start, fixedPaymentDates(), fixedDayCount);
    }

    /** PV of the floating leg per unit notional. */
    public double floatLegPv(DiscountCurve discount, DiscountCurve projection) {
        return Legs.floatLeg(discount, projection, start, floatPaymentDates(), floatDayCount);
    }

    /** Par fixed rate. */
    public double parRate(DiscountCurve discount, DiscountCurve projection) {
        return floatLegPv(discount, projection) / annuity(discount);
    }

    /** PV per unit notional of paying {@link #fixedRate()} and receiving floating. */
    public double payerPv(DiscountCurve discount, DiscountCurve projection) {
        return floatLegPv(discount, projection) - fixedRate * annuity(discount);
    }

    @Override
    public LocalDate pillarDate() {
        LocalDate a = fixedPaymentDates().getLast();
        LocalDate b = floatPaymentDates().getLast();
        return a.isAfter(b) ? a : b;
    }

    @Override
    public double quote() {
        return fixedRate;
    }

    @Override
    public InterestRateSwap withQuote(double quote) {
        return new InterestRateSwap(start, end, quote, fixedPerYear, fixedDayCount, floatPerYear,
                floatDayCount, calendar, roll);
    }

    @Override
    public double impliedQuote(DiscountCurve discount, DiscountCurve projection) {
        return parRate(discount, projection);
    }
}
