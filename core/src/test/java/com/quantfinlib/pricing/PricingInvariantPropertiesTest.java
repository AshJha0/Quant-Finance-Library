package com.quantfinlib.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quantfinlib.pricing.BinomialTree.ExerciseStyle;
import com.quantfinlib.pricing.BlackScholes.OptionType;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * Model-free pricing invariants checked over seeded random inputs. Evidence class:
 * self-consistency against textbook no-arbitrage results that hold in any arbitrage-free
 * model (Merton 1973, "Theory of rational option pricing", Bell Journal of Economics 4(1);
 * Hull, Options, Futures and Other Derivatives, chapter 11):
 *
 * <ul>
 *   <li>put-call parity C - P = S e^(-qT) - K e^(-rT) (Black-Scholes) and
 *       C - P = e^(-rT) (F - K) (Black-76);</li>
 *   <li>bounds max(0, S e^(-qT) - K e^(-rT)) &lt;= C &lt;= S e^(-qT) and
 *       max(0, K e^(-rT) - S e^(-qT)) &lt;= P &lt;= K e^(-rT);</li>
 *   <li>a call falls and a put rises with strike, both rise with volatility, a call rises
 *       with spot;</li>
 *   <li>convexity in strike (a butterfly is not negative);</li>
 *   <li>American is worth at least European, and at least intrinsic, on the same tree;</li>
 *   <li>implied volatility inverts the price where vega is not negligible.</li>
 * </ul>
 *
 * <p>Each test draws {@code qfl.cases} cases (default 1000) from a {@link SplittableRandom}
 * with a fixed seed; a failure message names the seed and case index, so it is reproducible.
 * Ranges: spot 1 to 1000, K/S 0.5 to 2, rate -2% to 10%, carry 0 to 8%, volatility 5% to
 * 100%, expiry 0.01 to 5 years.</p>
 */
class PricingInvariantPropertiesTest {

    private static final double REL = 1e-9;
    private static final int CASES = Integer.getInteger("qfl.cases", 1000);

    /** One random market. */
    private record Mkt(double s, double k, double r, double q, double v, double t, String id) {
    }

    private interface Check {
        void run(Mkt m);
    }

    private static void forAll(long seed, double sLo, double sHi, double mLo, double mHi, double rLo, double rHi,
            double qHi, double vLo, double vHi, double tLo, double tHi, int cases, Check c) {
        SplittableRandom rnd = new SplittableRandom(seed);
        for (int i = 0; i < cases; i++) {
            double s = rnd.nextDouble(sLo, sHi);
            Mkt m = new Mkt(s, s * rnd.nextDouble(mLo, mHi), rnd.nextDouble(rLo, rHi), rnd.nextDouble(0, qHi),
                    rnd.nextDouble(vLo, vHi), rnd.nextDouble(tLo, tHi), "seed " + seed + " case " + i);
            c.run(m);
        }
    }

    private static void forAll(long seed, Check c) {
        forAll(seed, 1, 1000, 0.5, 2, -0.02, 0.1, 0.08, 0.05, 1, 0.01, 5, CASES, c);
    }

    private static double tol(Mkt m) {
        return REL * (m.s() + m.k()) + 1e-12;
    }

    private static double bs(OptionType ty, Mkt m, double s, double k, double v) {
        return BlackScholes.price(ty, s, k, m.r(), m.q(), v, m.t());
    }

    @Test
    void blackScholesPutCallParity() {
        forAll(101L, m -> assertEquals(m.s() * Math.exp(-m.q() * m.t()) - m.k() * Math.exp(-m.r() * m.t()),
                bs(OptionType.CALL, m, m.s(), m.k(), m.v()) - bs(OptionType.PUT, m, m.s(), m.k(), m.v()), tol(m),
                m::toString));
    }

    @Test
    void black76PutCallParity() {
        forAll(102L, m -> {
            double c = Black76.price(OptionType.CALL, m.s(), m.k(), m.r(), m.v(), m.t());
            double p = Black76.price(OptionType.PUT, m.s(), m.k(), m.r(), m.v(), m.t());
            assertEquals(Math.exp(-m.r() * m.t()) * (m.s() - m.k()), c - p, tol(m), m::toString);
        });
    }

    @Test
    void noArbitrageBounds() {
        forAll(103L, m -> {
            double sd = m.s() * Math.exp(-m.q() * m.t());
            double kd = m.k() * Math.exp(-m.r() * m.t());
            double c = bs(OptionType.CALL, m, m.s(), m.k(), m.v());
            double p = bs(OptionType.PUT, m, m.s(), m.k(), m.v());
            double e = tol(m);
            assertTrue(c >= Math.max(0, sd - kd) - e && c <= sd + e, () -> "call " + c + " " + m);
            assertTrue(p >= Math.max(0, kd - sd) - e && p <= kd + e, () -> "put " + p + " " + m);
        });
    }

    @Test
    void monotoneInStrikeSpotAndVol() {
        forAll(104L, m -> {
            double e = tol(m);
            double dk = 0.01 * m.k();
            assertTrue(bs(OptionType.CALL, m, m.s(), m.k() + dk, m.v()) <= bs(OptionType.CALL, m, m.s(), m.k(), m.v())
                    + e, m::toString);
            assertTrue(bs(OptionType.PUT, m, m.s(), m.k() + dk, m.v()) >= bs(OptionType.PUT, m, m.s(), m.k(), m.v())
                    - e, m::toString);
            assertTrue(bs(OptionType.CALL, m, m.s() * 1.01, m.k(), m.v())
                    >= bs(OptionType.CALL, m, m.s(), m.k(), m.v()) - e, m::toString);
            for (OptionType ty : OptionType.values()) {
                assertTrue(bs(ty, m, m.s(), m.k(), m.v() + 0.05) >= bs(ty, m, m.s(), m.k(), m.v()) - e,
                        () -> ty + " vs vol " + m);
            }
        });
    }

    @Test
    void convexInStrike() {
        forAll(105L, m -> {
            double h = 0.02 * m.k();
            for (OptionType ty : OptionType.values()) {
                double fly = bs(ty, m, m.s(), m.k() - h, m.v()) - 2 * bs(ty, m, m.s(), m.k(), m.v())
                        + bs(ty, m, m.s(), m.k() + h, m.v());
                assertTrue(fly >= -4 * tol(m), () -> ty + " butterfly " + fly + " " + m);
            }
        });
    }

    @Test
    void americanNotBelowEuropean() {
        forAll(106L, 10, 200, 0.7, 1.4, 0, 0.08, 0.06, 0.1, 0.6, 0.1, 2, Math.max(1, CASES / 5), m -> {
            for (OptionType ty : OptionType.values()) {
                double am = BinomialTree.price(ty, ExerciseStyle.AMERICAN, m.s(), m.k(), m.r(), m.q(), m.v(), m.t(),
                        100);
                double eu = BinomialTree.price(ty, ExerciseStyle.EUROPEAN, m.s(), m.k(), m.r(), m.q(), m.v(), m.t(),
                        100);
                assertTrue(am >= eu - tol(m), () -> ty + " american " + am + " < european " + eu + " " + m);
                assertTrue(am >= BlackScholes.intrinsic(ty, m.s(), m.k()) - tol(m), () -> ty + " intrinsic " + m);
            }
        });
    }

    @Test
    void impliedVolInvertsPrice() {
        forAll(107L, 10, 500, 0.7, 1.4, 0, 0.08, 0.05, 0.05, 1, 0.05, 3, CASES, m -> {
            for (OptionType ty : OptionType.values()) {
                double px = bs(ty, m, m.s(), m.k(), m.v());
                if (BlackScholes.vega(m.s(), m.k(), m.r(), m.q(), m.v(), m.t()) < 1e-4 * m.s()) {
                    continue;
                }
                double iv = BlackScholes.impliedVol(ty, px, m.s(), m.k(), m.r(), m.q(), m.t());
                assertEquals(m.v(), iv, 1e-7, () -> ty + " price " + px + " " + m);
            }
        });
    }
}
