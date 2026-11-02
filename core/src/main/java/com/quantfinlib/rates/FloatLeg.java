package com.quantfinlib.rates;

import java.util.List;

/**
 * The floating leg of a {@link VanillaSwap}: either a compounded overnight
 * leg ({@link OvernightLeg}, SOFR-style) or a term-rate leg
 * ({@link TermRateLeg}, IBOR-style). Values are per unit notional; the
 * valuation date is the discount curve's.
 */
public sealed interface FloatLeg permits OvernightLeg, TermRateLeg {

    /**
     * The leg's accrual periods.
     *
     * @return the periods, ascending
     */
    List<LegConvention.Period> periods();

    /**
     * The coupon rate (index rate plus spread) of one period, from published
     * fixings where they exist and the projection curve otherwise.
     *
     * @param period     one of {@link #periods()}
     * @param projection projection curve (its valuation date is the valuation date)
     * @return the annualised coupon rate
     */
    double couponRate(LegConvention.Period period, DiscountCurve projection);

    /**
     * PV per unit notional of the coupons not yet paid (payment date after the
     * discount curve's valuation date).
     *
     * @param discount   discount curve
     * @param projection projection curve
     * @return present value
     */
    default double pv(DiscountCurve discount, DiscountCurve projection) {
        double pv = 0;
        for (LegConvention.Period p : periods()) {
            if (p.paymentDate().isAfter(discount.valuationDate())) {
                pv += couponRate(p, projection) * p.yearFraction()
                        * discount.discountFactor(p.paymentDate());
            }
        }
        return pv;
    }
}
