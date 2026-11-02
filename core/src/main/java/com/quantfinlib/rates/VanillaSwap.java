package com.quantfinlib.rates;

import java.util.Objects;

/**
 * A fixed-for-floating swap built from two convention-driven legs: the
 * replacement for the single-curve, annual, {@code tau = 1} methods of the
 * {@code SwapPricer} (removed in 4.0). Each leg carries its own frequency, day
 * count, calendar, roll, stub and payment lag ({@link LegConvention}); the
 * floating leg is a compounded overnight leg ({@link OvernightLeg}, with
 * lookback, observation shift, lockout and realised fixings) or a term-rate
 * leg ({@link TermRateLeg}).
 *
 * <p>Formal statement, per unit notional, valuation date = the discount
 * curve's, only coupons paid after it counted:</p>
 * <pre>
 *   payer PV  = PV_float - K * A
 *   par rate  = PV_float / A          (the K that sets payer PV to zero)
 * </pre>
 * <p>with {@code A} the {@link FixedLeg#annuity}. A seasoned trade is valued
 * the same way: past coupons are dropped, the current coupon combines
 * published fixings and forecasts.</p>
 *
 * <p>Worked example: a 5y SOFR OIS, annual both legs, ACT/360, 2-day payment
 * lag, 2-day lookback: {@code new VanillaSwap(new FixedLeg(s, e, k, conv),
 * new OvernightLeg(s, e, 0, conv, new OvernightConvention(2, false, 0), fixings))};
 * its par rate on a curve is pinned against QuantLib in the golden test.</p>
 *
 * @param fixed    the fixed leg
 * @param floating the floating leg
 */
public record VanillaSwap(FixedLeg fixed, FloatLeg floating) {

    /** Validates the fields. */
    public VanillaSwap {
        Objects.requireNonNull(fixed, "fixed");
        Objects.requireNonNull(floating, "floating");
    }

    /**
     * Fixed-leg annuity per unit notional.
     *
     * @param discount discount curve
     * @return the annuity
     */
    public double annuity(DiscountCurve discount) {
        return fixed.annuity(discount);
    }

    /**
     * Floating-leg PV per unit notional.
     *
     * @param discount   discount curve
     * @param projection projection curve
     * @return present value
     */
    public double floatLegPv(DiscountCurve discount, DiscountCurve projection) {
        return floating.pv(discount, projection);
    }

    /**
     * Par fixed rate.
     *
     * @param discount   discount curve
     * @param projection projection curve
     * @return the fixed rate that prices the swap at zero
     */
    public double parRate(DiscountCurve discount, DiscountCurve projection) {
        return floatLegPv(discount, projection) / annuity(discount);
    }

    /**
     * PV per unit notional of paying fixed and receiving floating.
     *
     * @param discount   discount curve
     * @param projection projection curve
     * @return present value
     */
    public double payerPv(DiscountCurve discount, DiscountCurve projection) {
        return floatLegPv(discount, projection) - fixed.pv(discount);
    }

    /**
     * PV per unit notional of receiving fixed and paying floating.
     *
     * @param discount   discount curve
     * @param projection projection curve
     * @return present value
     */
    public double receiverPv(DiscountCurve discount, DiscountCurve projection) {
        return -payerPv(discount, projection);
    }
}
