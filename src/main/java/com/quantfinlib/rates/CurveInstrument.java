package com.quantfinlib.rates;

import java.time.LocalDate;

/**
 * A quoted market instrument a {@link MultiCurveBootstrap} fits a curve to.
 *
 * <p>Each instrument knows its market quote (a rate, as a decimal) and how
 * to compute the same quantity implied by a discount curve and a projection
 * curve. The bootstrap solves for the curve on which
 * {@code impliedQuote(discount, projection) == quote()} for every input.
 * When a curve is fitted on its own (an OIS curve, which discounts and
 * projects the overnight index), both arguments are that curve.</p>
 */
public sealed interface CurveInstrument
        permits Deposit, Fra, RateFuture, OisSwap, InterestRateSwap {

    /** The last date the instrument depends on; the bootstrap places a pillar there. */
    LocalDate pillarDate();

    /** The market quote as a decimal rate (0.03 = 3%). */
    double quote();

    /** The same instrument with a different quote (used for bump-and-rebuild risk). */
    CurveInstrument withQuote(double quote);

    /** The quote implied by the curves, in the units of {@link #quote()}. */
    double impliedQuote(DiscountCurve discount, DiscountCurve projection);
}
