package com.quantfinlib.rates;

import java.time.LocalDate;
import java.util.Objects;

/**
 * A cash deposit over {@code [start, end]} at simple rate {@code rate}:
 * {@code 1} at start grows to {@code 1 + rate * tau} at end, tau from
 * {@code dayCount}. Implied rate: the projection curve's simple forward
 * {@code (P(start)/P(end) - 1)/tau}. Dates are taken as already adjusted.
 */
public record Deposit(LocalDate start, LocalDate end, double rate, DayCount dayCount)
        implements CurveInstrument {

    public Deposit {
        Legs.requirePeriod(start, end);
        Objects.requireNonNull(dayCount, "dayCount");
        Legs.requireFinite(rate, "rate");
    }

    @Override
    public LocalDate pillarDate() {
        return end;
    }

    @Override
    public double quote() {
        return rate;
    }

    @Override
    public Deposit withQuote(double quote) {
        return new Deposit(start, end, quote, dayCount);
    }

    @Override
    public double impliedQuote(DiscountCurve discount, DiscountCurve projection) {
        return projection.forwardRate(start, end, dayCount);
    }
}
