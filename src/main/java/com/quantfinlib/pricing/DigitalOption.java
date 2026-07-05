package com.quantfinlib.pricing;

import com.quantfinlib.pricing.BlackScholes.OptionType;
import com.quantfinlib.util.MathUtils;

/**
 * European digital (binary) options under Black-Scholes — the building
 * blocks of the first-generation FX exotics book.
 *
 * <p>Parameter conventions match {@link BlackScholes}: {@code rate} is the
 * domestic (quote-currency) rate, {@code carry} the continuous yield on the
 * underlying — the foreign rate for FX (Garman-Kohlhagen), the dividend
 * yield for equities — so the forward is {@code S·e^{(r−q)t}}.</p>
 *
 * <ul>
 *   <li><b>Cash-or-nothing</b>: pays a fixed amount if the option finishes
 *       in the money — {@code payout·e^{−rt}·N(±d2)}. This is the
 *       market-standard "European digital".</li>
 *   <li><b>Asset-or-nothing</b>: pays the underlying itself —
 *       {@code S·e^{−qt}·N(±d1)}. A vanilla decomposes exactly into
 *       asset-or-nothing minus strike × cash-or-nothing, which the tests
 *       assert.</li>
 * </ul>
 */
public final class DigitalOption {

    private DigitalOption() {
    }

    /** Fixed payout if spot finishes beyond the strike (call: above, put: below). */
    public static double cashOrNothing(OptionType type, double spot, double strike,
                                       double rate, double carry, double vol,
                                       double timeYears, double payout) {
        validate(spot, strike, vol, timeYears);
        if (timeYears == 0) {
            boolean itm = type == OptionType.CALL ? spot > strike : spot < strike;
            return itm ? payout : 0;
        }
        double d2 = d2(spot, strike, rate, carry, vol, timeYears);
        double df = Math.exp(-rate * timeYears);
        return payout * df * MathUtils.normCdf(type.sign() * d2);
    }

    /** Pays one unit of the underlying if the option finishes in the money. */
    public static double assetOrNothing(OptionType type, double spot, double strike,
                                        double rate, double carry, double vol,
                                        double timeYears) {
        validate(spot, strike, vol, timeYears);
        if (timeYears == 0) {
            boolean itm = type == OptionType.CALL ? spot > strike : spot < strike;
            return itm ? spot : 0;
        }
        double d1 = d2(spot, strike, rate, carry, vol, timeYears) + vol * Math.sqrt(timeYears);
        return spot * Math.exp(-carry * timeYears) * MathUtils.normCdf(type.sign() * d1);
    }

    private static double d2(double spot, double strike, double rate, double carry,
                             double vol, double t) {
        return (Math.log(spot / strike) + (rate - carry - 0.5 * vol * vol) * t)
                / (vol * Math.sqrt(t));
    }

    private static void validate(double spot, double strike, double vol, double t) {
        if (spot <= 0 || strike <= 0 || vol <= 0 || t < 0) {
            throw new IllegalArgumentException(
                    "spot, strike, vol must be > 0 and timeYears >= 0");
        }
    }
}
