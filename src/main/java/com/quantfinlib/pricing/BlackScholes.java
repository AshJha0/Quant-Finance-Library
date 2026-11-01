package com.quantfinlib.pricing;

import com.quantfinlib.util.MathUtils;

/**
 * Black-Scholes-Merton option pricing and Greeks with a continuous carry
 * yield {@code q}: set {@code q} to the dividend yield for equities or the
 * foreign interest rate for FX (Garman-Kohlhagen). All rates and volatility
 * are annualized; theta is per year; vega/rho are per 1.00 change.
 *
 * <p>With {@code vol <= 0} every function returns the deterministic limit
 * instead of dividing by σ√T: delta is the discounted in-the-money indicator
 * on the forward (one half at F = K), gamma is 0, vega is 0 except exactly
 * at the forward, theta and rho are the derivatives of the discounted forward
 * intrinsic value.</p>
 */
public final class BlackScholes {

    public enum OptionType {
        CALL, PUT;

        public int sign() {
            return this == CALL ? 1 : -1;
        }
    }

    /** Full Greek set for one option. */
    public record Greeks(double price, double delta, double gamma, double vega,
                         double theta, double rho) {
    }

    private BlackScholes() {
    }

    public static double price(OptionType type, double spot, double strike,
                               double rate, double carry, double vol, double timeYears) {
        if (timeYears <= 0) {
            return intrinsic(type, spot, strike);
        }
        if (vol <= 0) {
            // Deterministic world: discounted forward intrinsic. Without
            // this branch the ATM-forward case is 0/0 in d1 and the price
            // comes back NaN (off-ATM it survives only because +/-Inf
            // happens to hit the right CDF tail).
            return Math.max(0, type.sign() * (spot * Math.exp(-carry * timeYears)
                    - strike * Math.exp(-rate * timeYears)));
        }
        double d1 = d1(spot, strike, rate, carry, vol, timeYears);
        double d2 = d1 - vol * Math.sqrt(timeYears);
        double df = Math.exp(-rate * timeYears);
        double cf = Math.exp(-carry * timeYears);
        if (type == OptionType.CALL) {
            return spot * cf * MathUtils.normCdf(d1) - strike * df * MathUtils.normCdf(d2);
        }
        return strike * df * MathUtils.normCdf(-d2) - spot * cf * MathUtils.normCdf(-d1);
    }

    public static double delta(OptionType type, double spot, double strike,
                               double rate, double carry, double vol, double timeYears) {
        if (timeYears <= 0) {
            return intrinsicDelta(type, spot, strike);
        }
        if (vol <= 0) {
            return Math.exp(-carry * timeYears) * type.sign()
                    * zeroVolProb(type.sign(), spot, strike, rate, carry, timeYears);
        }
        double d1 = d1(spot, strike, rate, carry, vol, timeYears);
        double cf = Math.exp(-carry * timeYears);
        return type == OptionType.CALL
                ? cf * MathUtils.normCdf(d1)
                : cf * (MathUtils.normCdf(d1) - 1);
    }

    public static double gamma(double spot, double strike, double rate, double carry,
                               double vol, double timeYears) {
        if (timeYears <= 0) {
            return 0;
        }
        if (vol <= 0) {
            // Zero away from the forward; a Dirac mass at F = K, reported as 0.
            return 0;
        }
        double d1 = d1(spot, strike, rate, carry, vol, timeYears);
        return Math.exp(-carry * timeYears) * MathUtils.normPdf(d1)
                / (spot * vol * Math.sqrt(timeYears));
    }

    /** Per 1.00 change in volatility (divide by 100 for per-vol-point). */
    public static double vega(double spot, double strike, double rate, double carry,
                              double vol, double timeYears) {
        if (timeYears <= 0) {
            return 0;
        }
        if (vol <= 0) {
            // Limit as vol -> 0: zero unless the option is exactly at the forward.
            double fwd = spot * Math.exp((rate - carry) * timeYears);
            return Double.compare(fwd, strike) == 0
                    ? spot * Math.exp(-carry * timeYears) * MathUtils.normPdf(0) * Math.sqrt(timeYears)
                    : 0;
        }
        double d1 = d1(spot, strike, rate, carry, vol, timeYears);
        return spot * Math.exp(-carry * timeYears) * MathUtils.normPdf(d1) * Math.sqrt(timeYears);
    }

    /** Per year (divide by 365 for per-calendar-day). */
    public static double theta(OptionType type, double spot, double strike,
                               double rate, double carry, double vol, double timeYears) {
        if (timeYears <= 0) {
            return 0;
        }
        if (vol <= 0) {
            // Derivative of the deterministic price theta*(S e^{-qT} - K e^{-rT})^+.
            int w = type.sign();
            double n1 = zeroVolProb(w, spot, strike, rate, carry, timeYears);
            double n2 = zeroVolProb(w, spot, strike, rate, carry, timeYears);
            return w * (carry * spot * Math.exp(-carry * timeYears) * n1
                    - rate * strike * Math.exp(-rate * timeYears) * n2);
        }
        double sqrtT = Math.sqrt(timeYears);
        double d1 = d1(spot, strike, rate, carry, vol, timeYears);
        double d2 = d1 - vol * sqrtT;
        double cf = Math.exp(-carry * timeYears);
        double df = Math.exp(-rate * timeYears);
        double common = -spot * cf * MathUtils.normPdf(d1) * vol / (2 * sqrtT);
        if (type == OptionType.CALL) {
            return common - rate * strike * df * MathUtils.normCdf(d2)
                    + carry * spot * cf * MathUtils.normCdf(d1);
        }
        return common + rate * strike * df * MathUtils.normCdf(-d2)
                - carry * spot * cf * MathUtils.normCdf(-d1);
    }

    /** Per 1.00 change in the domestic rate. */
    public static double rho(OptionType type, double spot, double strike,
                             double rate, double carry, double vol, double timeYears) {
        if (timeYears <= 0) {
            return 0;
        }
        if (vol <= 0) {
            int w = type.sign();
            return w * strike * timeYears * Math.exp(-rate * timeYears)
                    * zeroVolProb(w, spot, strike, rate, carry, timeYears);
        }
        double d2 = d1(spot, strike, rate, carry, vol, timeYears) - vol * Math.sqrt(timeYears);
        double df = Math.exp(-rate * timeYears);
        return type == OptionType.CALL
                ? strike * timeYears * df * MathUtils.normCdf(d2)
                : -strike * timeYears * df * MathUtils.normCdf(-d2);
    }

    public static Greeks greeks(OptionType type, double spot, double strike,
                                double rate, double carry, double vol, double timeYears) {
        return new Greeks(
                price(type, spot, strike, rate, carry, vol, timeYears),
                delta(type, spot, strike, rate, carry, vol, timeYears),
                gamma(spot, strike, rate, carry, vol, timeYears),
                vega(spot, strike, rate, carry, vol, timeYears),
                theta(type, spot, strike, rate, carry, vol, timeYears),
                rho(type, spot, strike, rate, carry, vol, timeYears));
    }

    /**
     * Implied volatility from a market price, through {@link ImpliedVolatility#black}
     * on the forward F = S e^{(r-q)T} and the undiscounted price. Accurate to a
     * few ulps of σ wherever the price itself carries information, including
     * deep wings and very short or long expiries. Returns NaN when the price
     * is outside the attainable range (below intrinsic, or at or above the
     * discounted zero-strike bound) and 0 when it equals intrinsic exactly.
     */
    public static double impliedVol(OptionType type, double marketPrice, double spot,
                                    double strike, double rate, double carry, double timeYears) {
        if (!(spot > 0) || !Double.isFinite(rate) || !Double.isFinite(carry)) {
            return Double.NaN;
        }
        double forward = spot * Math.exp((rate - carry) * timeYears);
        return ImpliedVolatility.black(type, marketPrice * Math.exp(rate * timeYears),
                forward, strike, timeYears);
    }

    public static double intrinsic(OptionType type, double spot, double strike) {
        return Math.max(0, type.sign() * (spot - strike));
    }

    private static double intrinsicDelta(OptionType type, double spot, double strike) {
        if (type == OptionType.CALL) {
            return spot > strike ? 1 : 0;
        }
        return spot < strike ? -1 : 0;
    }

    /**
     * Zero-vol limit of Φ(w·d1) (or Φ(w·d2)): 1 if the option finishes in
     * the money on the deterministic forward, 0 if out, 1/2 at F = K (the
     * limit of Φ(±σ√T/2) for d1/d2 at the forward).
     */
    private static double zeroVolProb(int w, double spot, double strike, double rate,
                                      double carry, double timeYears) {
        double fwd = spot * Math.exp((rate - carry) * timeYears);
        if (Double.compare(fwd, strike) == 0) {
            return 0.5;
        }
        return w * (fwd - strike) > 0 ? 1 : 0;
    }

    private static double d1(double spot, double strike, double rate, double carry,
                             double vol, double timeYears) {
        return (Math.log(spot / strike) + (rate - carry + 0.5 * vol * vol) * timeYears)
                / (vol * Math.sqrt(timeYears));
    }
}
