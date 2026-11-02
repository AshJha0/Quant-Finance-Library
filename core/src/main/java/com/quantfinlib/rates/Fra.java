package com.quantfinlib.rates;

import java.time.LocalDate;
import java.util.Objects;

/**
 * A forward rate agreement on the index period {@code [start, end]} at
 * fixed rate {@code rate}. The FRA settles at {@code start} the discounted
 * value of {@code (L - K) * tau}; its par rate is the projection forward
 * {@code L = (P_p(start)/P_p(end) - 1)/tau} whatever the discount curve,
 * because the settlement amount is discounted at the fixing itself (ISDA
 * FRA discounting). Dates are taken as already adjusted.
 */
public record Fra(LocalDate start, LocalDate end, double rate, DayCount dayCount)
        implements CurveInstrument {

    public Fra {
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
    public Fra withQuote(double quote) {
        return new Fra(start, end, quote, dayCount);
    }

    @Override
    public double impliedQuote(DiscountCurve discount, DiscountCurve projection) {
        return projection.forwardRate(start, end, dayCount);
    }
}
