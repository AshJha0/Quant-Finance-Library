package com.quantfinlib.pricing;

import com.quantfinlib.pricing.BlackScholes.OptionType;
import com.quantfinlib.stats.SpecialFunctions;

/**
 * Black (lognormal) implied volatility by a safeguarded Newton iteration on
 * the normalised Black function.
 *
 * <p>Normalisation follows Jäckel (2015), "Let's be rational", Wilmott
 * 2015(75): with x = ln(F/K) and s = σ√T the undiscounted price divided by
 * √(FK) is</p>
 *
 * <pre>  b(x, s, θ) = θ·[e^{x/2} Φ(θ(x/s + s/2)) − e^{−x/2} Φ(θ(x/s − s/2))]</pre>
 *
 * <p>and ∂b/∂s = e^{x/2} φ(x/s + s/2). An in-the-money quote is first turned
 * into the out-of-the-money one by put-call parity (removing the intrinsic
 * value, which is where accuracy is lost if the ITM option is inverted
 * directly), and the symmetry b(x, s, θ) = b(−x, s, −θ) reduces every case to
 * an OTM call with x ≤ 0.</p>
 *
 * <p>The solver is not Jäckel's rational-interpolation scheme. It uses the
 * same objective transformation idea: below the inflection point
 * s_c = √(2|x|), where b is convex in s, Newton runs on ln b (so prices of
 * 1e-200 converge as fast as prices of 1e-2); above it, where b is concave,
 * Newton runs on b itself. Every step is kept inside a bracket that shrinks
 * with each evaluation, so the iteration cannot diverge; it usually converges
 * to machine precision in under ten steps. Near the money the price is
 * formed as sinh(x/2) plus odd parts of Φ to avoid the cancellation in
 * Φ(d₁) − Φ(d₂) at very low total volatility.</p>
 *
 * <p>Return conventions: NaN for a price outside the attainable range (below
 * intrinsic, or at or above the zero-strike bound), 0 for a price exactly at
 * intrinsic, NaN for non-finite or non-positive inputs.</p>
 */
public final class ImpliedVolatility {

    private static final int MAX_ITER = 100;

    private ImpliedVolatility() {
    }

    /**
     * Black implied volatility from an undiscounted (forward) option price.
     *
     * @param forwardPrice option price divided by the discount factor
     * @return σ such that Black(F, K, σ, T) equals the price, or NaN
     */
    public static double black(OptionType type, double forwardPrice, double forward,
                               double strike, double timeYears) {
        if (!(forward > 0) || !(strike > 0) || !(timeYears > 0) || !Double.isFinite(forwardPrice)
                || forward == Double.POSITIVE_INFINITY || strike == Double.POSITIVE_INFINITY
                || timeYears == Double.POSITIVE_INFINITY) {
            return Double.NaN;
        }
        double theta = type.sign();
        double intrinsic = Math.max(theta * (forward - strike), 0);
        double otm = forwardPrice - intrinsic;
        if (otm < 0) {
            return Double.NaN;
        }
        double x = Math.log(forward / strike);
        if (theta * x > 0) {
            theta = -theta; // switch to the OTM option, same time value
        }
        // OTM upper bound: a call is worth less than F, a put less than K.
        double upper = theta > 0 ? forward : strike;
        if (otm >= upper) {
            return Double.NaN;
        }
        if (otm == 0) {
            return 0.0;
        }
        double beta = otm / Math.sqrt(forward * strike);
        double s = normalisedVol(theta > 0 ? x : -x, beta);
        return s / Math.sqrt(timeYears);
    }

    /**
     * Normalised Black price b(x, s, θ) as defined in the class comment.
     */
    public static double normalisedBlack(double x, double s, int theta) {
        if (theta < 0) {
            x = -x;
        }
        // Intrinsic part for the ITM side, then the OTM call value.
        double intrinsic = x > 0 ? 2 * Math.sinh(x / 2) : 0;
        return intrinsic + otmCall(-Math.abs(x), s);
    }

    /** OTM normalised call, x <= 0, s >= 0. */
    static double otmCall(double x, double s) {
        if (s <= 0) {
            return 0;
        }
        double d1 = x / s + s / 2;
        double d2 = x / s - s / 2;
        if (Math.abs(d1) < 0.6 && Math.abs(d2) < 0.6) {
            // e^{x/2}(1/2 + h1) − e^{−x/2}(1/2 + h2) with h = Φ − 1/2 odd.
            double h1 = halfOffset(d1);
            double h2 = halfOffset(d2);
            return Math.sinh(x / 2) + Math.exp(x / 2) * h1 - Math.exp(-x / 2) * h2;
        }
        double v = Math.exp(x / 2) * SpecialFunctions.normCdf(d1) - Math.exp(-x / 2) * SpecialFunctions.normCdf(d2);
        return Math.max(v, 0);
    }

    /** Φ(d) − 1/2 without cancellation for small |d| (Taylor series, |d| &lt; 0.6). */
    private static double halfOffset(double d) {
        // Φ(d) − 1/2 = φ(0) Σ (−1)^n d^{2n+1} / (2^n n! (2n+1))
        double d2 = d * d, term = d, sum = d;
        for (int n = 1; n < 40; n++) {
            term *= -d2 / (2 * n);
            double add = term / (2 * n + 1);
            sum += add;
            if (Math.abs(add) <= 1e-17 * Math.abs(sum)) {
                break;
            }
        }
        return 0.3989422804014327 * sum;
    }

    /** Solves otmCall(x, s) = beta for s, x <= 0, 0 < beta < e^{x/2}. */
    static double normalisedVol(double x, double beta) {
        double lo = 0, hi = Double.POSITIVE_INFINITY;
        double sc = Math.sqrt(2 * Math.abs(x));
        double bc = otmCall(x, sc);
        // Start: ATM-style guess s = sqrt(2 pi) beta near the money, else the
        // inflection point, which separates the convex and concave branches.
        double s;
        if (Math.abs(x) < 1e-12) {
            s = Math.sqrt(2 * Math.PI) * beta;
        } else if (beta < bc) {
            hi = sc;
            // Asymptotic seed from b ~ e^{x/2} φ(d1) s^3/(x^2) is awkward; take
            // the ln-b Newton from the inflection point, which is monotone here.
            s = sc;
        } else {
            lo = sc;
            s = sc;
        }
        if (s <= 0) {
            s = 1e-8;
        }
        double logBeta = Math.log(beta);
        for (int i = 0; i < MAX_ITER; i++) {
            double b = otmCall(x, s);
            double d1 = x / s + s / 2;
            double vega = Math.exp(x / 2 + SpecialFunctions.logNormPdf(d1));
            if (b > beta) {
                hi = Math.min(hi, s);
            } else if (b < beta) {
                lo = Math.max(lo, s);
            } else {
                return s;
            }
            double next;
            boolean useLog = s < sc || beta < 1e-3 * Math.exp(x / 2);
            if (b > 0 && vega > 0) {
                next = useLog ? s - (Math.log(b) - logBeta) * b / vega : s - (b - beta) / vega;
            } else {
                next = Double.NaN;
            }
            if (!(next > lo && next < hi)) {
                next = hi == Double.POSITIVE_INFINITY ? Math.max(2 * s, 1) : 0.5 * (lo + hi);
            }
            if (Math.abs(next - s) <= 4 * Math.ulp(s)
                    || (hi < Double.POSITIVE_INFINITY && hi - lo <= 4 * Math.ulp(hi))) {
                return next;
            }
            s = next;
        }
        return s;
    }
}
