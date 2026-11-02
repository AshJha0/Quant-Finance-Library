package com.quantfinlib.rates;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * An overnight indexed swap: fixed rate against the daily-compounded
 * overnight rate (SOFR, ESTR, SONIA), same schedule on both legs, payment
 * on the accrual end date (no payment lag).
 *
 * <p>Compounding the overnight rate over a period and discounting on the
 * same OIS curve telescopes: the floating leg is worth
 * {@code P(start) - P(end)}. The par rate is therefore</p>
 * <pre>
 *   K_par = (P(start) - P(t_n)) / sum_i tau_i P(t_i)
 * </pre>
 * <p>with tau from {@code dayCount} on the adjusted schedule from
 * {@link BusinessCalendar#schedule(LocalDate, LocalDate, int, BusinessCalendar.Roll)}.
 * Only the discount curve enters; the projection argument is ignored.</p>
 */
public record OisSwap(LocalDate start, LocalDate end, double fixedRate, int paymentsPerYear,
                      DayCount dayCount, BusinessCalendar calendar, BusinessCalendar.Roll roll)
        implements CurveInstrument {

    public OisSwap {
        Legs.requirePeriod(start, end);
        Objects.requireNonNull(dayCount, "dayCount");
        Objects.requireNonNull(calendar, "calendar");
        Objects.requireNonNull(roll, "roll");
        Legs.requireFinite(fixedRate, "fixedRate");
        Legs.payments(start, end, paymentsPerYear, calendar, roll);
    }

    /** Adjusted payment dates. */
    public List<LocalDate> paymentDates() {
        return Legs.payments(start, end, paymentsPerYear, calendar, roll);
    }

    /** {@code sum tau_i P(t_i)} per unit notional. */
    public double annuity(DiscountCurve discount) {
        return Legs.annuity(discount, start, paymentDates(), dayCount);
    }

    /** The par fixed rate on {@code discount}. */
    public double parRate(DiscountCurve discount) {
        return (discount.discountFactor(start) - discount.discountFactor(pillarDate()))
                / annuity(discount);
    }

    /** PV per unit notional of paying fixed, receiving the compounded overnight rate. */
    public double payerPv(DiscountCurve discount) {
        return annuity(discount) * (parRate(discount) - fixedRate);
    }

    @Override
    public LocalDate pillarDate() {
        return paymentDates().getLast();
    }

    @Override
    public double quote() {
        return fixedRate;
    }

    @Override
    public OisSwap withQuote(double quote) {
        return new OisSwap(start, end, quote, paymentsPerYear, dayCount, calendar, roll);
    }

    @Override
    public double impliedQuote(DiscountCurve discount, DiscountCurve projection) {
        return parRate(discount);
    }
}
