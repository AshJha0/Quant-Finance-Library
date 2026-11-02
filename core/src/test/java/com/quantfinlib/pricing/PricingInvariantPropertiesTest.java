package com.quantfinlib.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quantfinlib.pricing.BinomialTree.ExerciseStyle;
import com.quantfinlib.pricing.BlackScholes.OptionType;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;

/**
 * Model-free pricing invariants checked as jqwik properties over random inputs. Evidence
 * class: self-consistency, except that each invariant is a textbook no-arbitrage result that
 * holds for any arbitrage-free model (Merton 1973, "Theory of rational option pricing", Bell
 * Journal of Economics 4(1), theorems 1 to 3 and the put-call parity relation; Hull, Options,
 * Futures and Other Derivatives, chapter 11). The properties are:
 *
 * <ul>
 *   <li>put-call parity C - P = S e^(-qT) - K e^(-rT) for Black-Scholes and
 *       C - P = e^(-rT) (F - K) for Black-76;</li>
 *   <li>bounds max(0, S e^(-qT) - K e^(-rT)) &lt;= C &lt;= S e^(-qT) and
 *       max(0, K e^(-rT) - S e^(-qT)) &lt;= P &lt;= K e^(-rT);</li>
 *   <li>monotonicity: a call falls and a put rises with the strike, both rise with
 *       volatility, a call rises with spot;</li>
 *   <li>convexity in strike (butterfly is not negative);</li>
 *   <li>an American option is worth at least its European twin on the same tree;</li>
 *   <li>implied volatility inverts the price where vega is not negligible.</li>
 * </ul>
 *
 * <p>Ranges: spot 1 to 1000, moneyness K/S 0.5 to 2, rate -2% to 10%, carry (dividend yield)
 * 0 to 8%, volatility 5% to 100%, expiry 0.01 to 5 years. jqwik runs 1000 tries per
 * property by default and shrinks a failure to a small case.</p>
 */
class PricingInvariantPropertiesTest {

    private static final double REL = 1e-9;

    private static double tol(double s, double k) {
        return REL * (s + k) + 1e-12;
    }

    @Property
    void blackScholesPutCallParity(@ForAll @DoubleRange(min = 1, max = 1000) double s,
            @ForAll @DoubleRange(min = 0.5, max = 2) double m, @ForAll @DoubleRange(min = -0.02, max = 0.1) double r,
            @ForAll @DoubleRange(min = 0, max = 0.08) double q, @ForAll @DoubleRange(min = 0.05, max = 1) double v,
            @ForAll @DoubleRange(min = 0.01, max = 5) double t) {
        double k = s * m;
        double c = BlackScholes.price(OptionType.CALL, s, k, r, q, v, t);
        double p = BlackScholes.price(OptionType.PUT, s, k, r, q, v, t);
        assertEquals(s * Math.exp(-q * t) - k * Math.exp(-r * t), c - p, tol(s, k));
    }

    @Property
    void black76PutCallParity(@ForAll @DoubleRange(min = 1, max = 1000) double f,
            @ForAll @DoubleRange(min = 0.5, max = 2) double m, @ForAll @DoubleRange(min = -0.02, max = 0.1) double r,
            @ForAll @DoubleRange(min = 0.05, max = 1) double v, @ForAll @DoubleRange(min = 0.01, max = 5) double t) {
        double k = f * m;
        double c = Black76.price(OptionType.CALL, f, k, r, v, t);
        double p = Black76.price(OptionType.PUT, f, k, r, v, t);
        assertEquals(Math.exp(-r * t) * (f - k), c - p, tol(f, k));
    }

    @Property
    void noArbitrageBounds(@ForAll @DoubleRange(min = 1, max = 1000) double s,
            @ForAll @DoubleRange(min = 0.5, max = 2) double m, @ForAll @DoubleRange(min = -0.02, max = 0.1) double r,
            @ForAll @DoubleRange(min = 0, max = 0.08) double q, @ForAll @DoubleRange(min = 0.05, max = 1) double v,
            @ForAll @DoubleRange(min = 0.01, max = 5) double t) {
        double k = s * m;
        double sd = s * Math.exp(-q * t);
        double kd = k * Math.exp(-r * t);
        double c = BlackScholes.price(OptionType.CALL, s, k, r, q, v, t);
        double p = BlackScholes.price(OptionType.PUT, s, k, r, q, v, t);
        double e = tol(s, k);
        assertTrue(c >= Math.max(0, sd - kd) - e && c <= sd + e, "call " + c);
        assertTrue(p >= Math.max(0, kd - sd) - e && p <= kd + e, "put " + p);
    }

    @Property
    void monotoneInStrikeSpotAndVol(@ForAll @DoubleRange(min = 1, max = 1000) double s,
            @ForAll @DoubleRange(min = 0.5, max = 2) double m, @ForAll @DoubleRange(min = -0.02, max = 0.1) double r,
            @ForAll @DoubleRange(min = 0, max = 0.08) double q, @ForAll @DoubleRange(min = 0.05, max = 0.9) double v,
            @ForAll @DoubleRange(min = 0.01, max = 5) double t) {
        double k = s * m;
        double e = tol(s, k);
        double dk = 0.01 * k;
        assertTrue(BlackScholes.price(OptionType.CALL, s, k + dk, r, q, v, t)
                <= BlackScholes.price(OptionType.CALL, s, k, r, q, v, t) + e, "call vs strike");
        assertTrue(BlackScholes.price(OptionType.PUT, s, k + dk, r, q, v, t)
                >= BlackScholes.price(OptionType.PUT, s, k, r, q, v, t) - e, "put vs strike");
        assertTrue(BlackScholes.price(OptionType.CALL, s * 1.01, k, r, q, v, t)
                >= BlackScholes.price(OptionType.CALL, s, k, r, q, v, t) - e, "call vs spot");
        for (OptionType ty : OptionType.values()) {
            assertTrue(BlackScholes.price(ty, s, k, r, q, v + 0.05, t)
                    >= BlackScholes.price(ty, s, k, r, q, v, t) - e, ty + " vs vol");
        }
    }

    @Property
    void convexInStrike(@ForAll @DoubleRange(min = 1, max = 1000) double s,
            @ForAll @DoubleRange(min = 0.5, max = 2) double m, @ForAll @DoubleRange(min = -0.02, max = 0.1) double r,
            @ForAll @DoubleRange(min = 0, max = 0.08) double q, @ForAll @DoubleRange(min = 0.05, max = 1) double v,
            @ForAll @DoubleRange(min = 0.01, max = 5) double t) {
        double k = s * m;
        double h = 0.02 * k;
        for (OptionType ty : OptionType.values()) {
            double fly = BlackScholes.price(ty, s, k - h, r, q, v, t) - 2 * BlackScholes.price(ty, s, k, r, q, v, t)
                    + BlackScholes.price(ty, s, k + h, r, q, v, t);
            assertTrue(fly >= -tol(s, k) * 4, ty + " butterfly " + fly);
        }
    }

    @Property(tries = 200)
    void americanNotBelowEuropean(@ForAll @DoubleRange(min = 10, max = 200) double s,
            @ForAll @DoubleRange(min = 0.7, max = 1.4) double m, @ForAll @DoubleRange(min = 0, max = 0.08) double r,
            @ForAll @DoubleRange(min = 0, max = 0.06) double q, @ForAll @DoubleRange(min = 0.1, max = 0.6) double v,
            @ForAll @DoubleRange(min = 0.1, max = 2) double t) {
        double k = s * m;
        for (OptionType ty : OptionType.values()) {
            double am = BinomialTree.price(ty, ExerciseStyle.AMERICAN, s, k, r, q, v, t, 100);
            double eu = BinomialTree.price(ty, ExerciseStyle.EUROPEAN, s, k, r, q, v, t, 100);
            assertTrue(am >= eu - tol(s, k), ty + " american " + am + " < european " + eu);
            assertTrue(am >= BlackScholes.intrinsic(ty, s, k) - tol(s, k), ty + " below intrinsic");
        }
    }

    @Property
    void impliedVolInvertsPrice(@ForAll @DoubleRange(min = 10, max = 500) double s,
            @ForAll @DoubleRange(min = 0.7, max = 1.4) double m, @ForAll @DoubleRange(min = 0, max = 0.08) double r,
            @ForAll @DoubleRange(min = 0, max = 0.05) double q, @ForAll @DoubleRange(min = 0.05, max = 1) double v,
            @ForAll @DoubleRange(min = 0.05, max = 3) double t) {
        double k = s * m;
        for (OptionType ty : OptionType.values()) {
            double px = BlackScholes.price(ty, s, k, r, q, v, t);
            double vega = BlackScholes.vega(s, k, r, q, v, t);
            if (vega < 1e-4 * s) {
                continue;
            }
            double iv = BlackScholes.impliedVol(ty, px, s, k, r, q, t);
            assertEquals(v, iv, 1e-7, ty + " price " + px);
        }
    }
}
