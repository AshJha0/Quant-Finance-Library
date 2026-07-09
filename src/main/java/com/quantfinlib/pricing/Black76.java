package com.quantfinlib.pricing;

import com.quantfinlib.pricing.BlackScholes.OptionType;
import com.quantfinlib.util.MathUtils;

/**
 * Black-76 — the Black-Scholes sibling for options on FORWARDS and
 * futures: rates caps/floors and swaptions, commodity futures options,
 * bond futures options. The whole trick is that a forward has no carry
 * (it costs nothing to hold), so the underlying drift drops out and the
 * price is just the discounted Black formula on the forward itself:
 *
 * <pre>  call = df · [F·Φ(d₁) − K·Φ(d₂)],   d₁ = (ln(F/K) + σ²T/2)/(σ√T)</pre>
 *
 * <p>Equivalent to {@link BlackScholes} with {@code carry = 0} and
 * spot = forward — a test pins that identity — but the market quotes
 * these instruments IN Black-76 terms (a "cap vol" or a "futures option
 * vol" is a Black-76 σ), so the model deserves its own front door.
 * Static, allocation-free, NaN-transparent inputs produce NaN outputs
 * (research-lane pricing convention, matching BlackScholes).</p>
 */
public final class Black76 {

    private Black76() {
    }

    /** Discounted Black-76 price of a call/put on a forward. */
    public static double price(OptionType type, double forward, double strike,
                               double rate, double vol, double timeYears) {
        if (timeYears <= 0 || vol <= 0) {
            return Math.exp(-rate * Math.max(timeYears, 0))
                    * BlackScholes.intrinsic(type, forward, strike);
        }
        double df = Math.exp(-rate * timeYears);
        double sqrtT = Math.sqrt(timeYears);
        double d1 = (Math.log(forward / strike) + 0.5 * vol * vol * timeYears)
                / (vol * sqrtT);
        double d2 = d1 - vol * sqrtT;
        if (type == OptionType.CALL) {
            return df * (forward * MathUtils.normCdf(d1) - strike * MathUtils.normCdf(d2));
        }
        return df * (strike * MathUtils.normCdf(-d2) - forward * MathUtils.normCdf(-d1));
    }

    /** Sensitivity to the FORWARD (not spot): df·Φ(d₁) for calls. */
    public static double delta(OptionType type, double forward, double strike,
                               double rate, double vol, double timeYears) {
        if (timeYears <= 0 || vol <= 0) {
            double intrinsicDelta = type == OptionType.CALL
                    ? (forward > strike ? 1 : 0) : (forward < strike ? -1 : 0);
            return Math.exp(-rate * Math.max(timeYears, 0)) * intrinsicDelta;
        }
        double d1 = (Math.log(forward / strike) + 0.5 * vol * vol * timeYears)
                / (vol * Math.sqrt(timeYears));
        double df = Math.exp(-rate * timeYears);
        return type == OptionType.CALL
                ? df * MathUtils.normCdf(d1)
                : df * (MathUtils.normCdf(d1) - 1);
    }

    /** Vega per 1.00 of vol (divide by 100 for per-point). Same for calls and puts. */
    public static double vega(double forward, double strike, double rate,
                              double vol, double timeYears) {
        if (timeYears <= 0 || vol <= 0) {
            return 0;
        }
        double sqrtT = Math.sqrt(timeYears);
        double d1 = (Math.log(forward / strike) + 0.5 * vol * vol * timeYears)
                / (vol * sqrtT);
        return Math.exp(-rate * timeYears) * forward * MathUtils.normPdf(d1) * sqrtT;
    }

    /** Black-76 implied vol from a price, via bisection (NaN if unattainable). */
    public static double impliedVol(OptionType type, double marketPrice, double forward,
                                    double strike, double rate, double timeYears) {
        double lo = 1e-6;
        double hi = 5.0;
        if (marketPrice <= price(type, forward, strike, rate, lo, timeYears)
                || marketPrice >= price(type, forward, strike, rate, hi, timeYears)) {
            return Double.NaN;
        }
        for (int i = 0; i < 100; i++) {
            double mid = 0.5 * (lo + hi);
            if (price(type, forward, strike, rate, mid, timeYears) < marketPrice) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return 0.5 * (lo + hi);
    }
}
