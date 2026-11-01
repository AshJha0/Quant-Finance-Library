package com.quantfinlib.pricing;

import com.quantfinlib.pricing.BlackScholes.OptionType;
import com.quantfinlib.util.MathUtils;

/**
 * Bachelier (normal) model for options on a forward: the forward follows
 * dF = σ_N dW, so it can go negative and σ_N is quoted in absolute rate units
 * (0.0080 = 80 basis points a year). Rates desks quote swaptions and caps in
 * normal vol since negative rates made the lognormal model unusable
 * (Bachelier 1900; for the modern use see Andersen and Piterbarg 2010,
 * <i>Interest Rate Modeling</i>, vol. 1, section 7.1).
 *
 * <pre>  call = df · [(F − K) Φ(d) + σ_N √T φ(d)],   d = (F − K) / (σ_N √T)
 *   put  = df · [(K − F) Φ(−d) + σ_N √T φ(d)]</pre>
 *
 * <p>Forward and strike may be any finite numbers, including negative.
 * {@code rate} is the continuously compounded discount rate to the payment
 * date (pass 0 and multiply by an annuity for swaptions).</p>
 */
public final class Bachelier {

    private static final double SQRT_2PI = Math.sqrt(2 * Math.PI);

    private Bachelier() {
    }

    /** Discounted normal-model price. */
    public static double price(OptionType type, double forward, double strike,
                               double rate, double normalVol, double timeYears) {
        double df = Math.exp(-rate * Math.max(timeYears, 0));
        double w = type.sign();
        if (timeYears <= 0 || normalVol <= 0) {
            return df * Math.max(w * (forward - strike), 0);
        }
        double sd = normalVol * Math.sqrt(timeYears);
        double d = (forward - strike) / sd;
        return df * (w * (forward - strike) * MathUtils.normCdf(w * d) + sd * MathUtils.normPdf(d));
    }

    /** Sensitivity to the forward: df·Φ(d) for a call, −df·Φ(−d) for a put. */
    public static double delta(OptionType type, double forward, double strike,
                               double rate, double normalVol, double timeYears) {
        double df = Math.exp(-rate * Math.max(timeYears, 0));
        int w = type.sign();
        if (timeYears <= 0 || normalVol <= 0) {
            double m = w * (forward - strike);
            return df * w * (m > 0 ? 1 : m == 0 ? 0.5 : 0);
        }
        double d = (forward - strike) / (normalVol * Math.sqrt(timeYears));
        return df * w * MathUtils.normCdf(w * d);
    }

    /** Vega per 1.00 of normal vol: df·√T·φ(d). */
    public static double vega(double forward, double strike, double rate,
                              double normalVol, double timeYears) {
        if (timeYears <= 0) {
            return 0;
        }
        double df = Math.exp(-rate * timeYears);
        if (normalVol <= 0) {
            return forward == strike ? df * Math.sqrt(timeYears) / SQRT_2PI : 0;
        }
        double d = (forward - strike) / (normalVol * Math.sqrt(timeYears));
        return df * Math.sqrt(timeYears) * MathUtils.normPdf(d);
    }

    /**
     * Implied normal vol from a discounted price. NaN for a price below
     * intrinsic or for invalid inputs, 0 at intrinsic.
     *
     * <p>Out of the money the time value is v = σ√T·h(u) with
     * u = |F − K|/(σ√T) and h(u) = φ(u) − u·Φ(−u), h'(u) = −Φ(−u)
     * (Jäckel 2017, "Implied normal volatility", Wilmott). The solver runs a
     * bracketed Newton iteration on ln(h(u)/u) = ln(v/|F − K|), which is
     * smooth and monotone; at the money σ = v·√(2π/T) exactly.</p>
     */
    public static double impliedVol(OptionType type, double price, double forward,
                                    double strike, double rate, double timeYears) {
        if (!(timeYears > 0) || !Double.isFinite(price) || !Double.isFinite(forward)
                || !Double.isFinite(strike) || !Double.isFinite(rate)
                || timeYears == Double.POSITIVE_INFINITY) {
            return Double.NaN;
        }
        double undiscounted = price * Math.exp(rate * timeYears);
        double intrinsic = Math.max(type.sign() * (forward - strike), 0);
        double v = undiscounted - intrinsic; // time value = OTM option value by parity
        double sqrtT = Math.sqrt(timeYears);
        if (v < 0) {
            return Double.NaN;
        }
        if (v == 0) {
            return 0.0;
        }
        double a = Math.abs(forward - strike);
        if (a == 0) {
            return v * SQRT_2PI / sqrtT;
        }
        double target = Math.log(v / a);
        // Seed: near the money h(u) ~ φ(0) so u ~ φ(0)/g; far out h ~ φ(u)/u^2.
        double g = v / a;
        double u = g > 0.2 ? 1 / (SQRT_2PI * g) : Math.sqrt(Math.max(-2 * Math.log(g * SQRT_2PI), 1));
        double lo = 0, hi = Double.POSITIVE_INFINITY;
        for (int i = 0; i < 100; i++) {
            double h = hOf(u);
            double f = Math.log(h / u) - target; // decreasing in u
            if (!(h > 0)) {
                f = Double.NEGATIVE_INFINITY; // h underflowed: u is far too large
            }
            if (f > 0) {
                lo = u;
            } else if (f < 0) {
                hi = u;
            } else {
                break;
            }
            double slope = -MathUtils.normCdf(-u) / h - 1 / u;
            double next = u - f / slope;
            if (!(next > lo && next < hi)) {
                next = hi == Double.POSITIVE_INFINITY ? 2 * u + 1 : 0.5 * (lo + hi);
            }
            if (Math.abs(next - u) <= 4 * Math.ulp(u)) {
                u = next;
                break;
            }
            u = next;
        }
        return a / (u * sqrtT);
    }

    /** h(u) = φ(u) − u Φ(−u), written through the Mills ratio for large u. */
    private static double hOf(double u) {
        if (u < 2) {
            return MathUtils.normPdf(u) - u * MathUtils.normCdf(-u);
        }
        double phi = MathUtils.normPdf(u);
        double mills = MathUtils.normCdf(-u) / phi;
        return phi * (1 - u * mills);
    }
}
