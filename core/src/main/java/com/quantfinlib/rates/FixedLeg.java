package com.quantfinlib.rates;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * The fixed leg of a {@link VanillaSwap}: coupons {@code rate * tau_i} paid
 * on each period's payment date, with the schedule, day count, stub and
 * payment lag from a {@link LegConvention}. Values are per unit notional.
 *
 * <pre>
 *   annuity = sum_{i: pay_i &gt; valuation} tau_i P_d(pay_i)
 *   pv      = rate * annuity
 * </pre>
 *
 * @param start      unadjusted effective date
 * @param end        unadjusted maturity
 * @param rate       fixed rate (decimal)
 * @param convention schedule, day count, calendar and payment lag
 */
public record FixedLeg(LocalDate start, LocalDate end, double rate, LegConvention convention) {

    /** Validates the fields. */
    public FixedLeg {
        Legs.requirePeriod(start, end);
        Legs.requireFinite(rate, "rate");
        Objects.requireNonNull(convention, "convention");
        convention.periods(start, end);
    }

    /**
     * The leg's accrual periods.
     *
     * @return the periods, ascending
     */
    public List<LegConvention.Period> periods() {
        return convention.periods(start, end);
    }

    /**
     * Annuity per unit notional and unit rate, over the coupons not yet paid.
     *
     * @param discount discount curve
     * @return the annuity
     */
    public double annuity(DiscountCurve discount) {
        double a = 0;
        for (LegConvention.Period p : periods()) {
            if (p.paymentDate().isAfter(discount.valuationDate())) {
                a += p.yearFraction() * discount.discountFactor(p.paymentDate());
            }
        }
        return a;
    }

    /**
     * PV per unit notional of the unpaid fixed coupons.
     *
     * @param discount discount curve
     * @return present value
     */
    public double pv(DiscountCurve discount) {
        return rate * annuity(discount);
    }

    /**
     * The same leg with a different fixed rate.
     *
     * @param newRate fixed rate
     * @return the new leg
     */
    public FixedLeg withRate(double newRate) {
        return new FixedLeg(start, end, newRate, convention);
    }
}
