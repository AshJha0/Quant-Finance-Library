package com.quantfinlib.pricing;

import com.quantfinlib.util.MathUtils;
import org.junit.jupiter.api.Test;

import static com.quantfinlib.pricing.BlackScholes.OptionType.CALL;
import static com.quantfinlib.pricing.BlackScholes.OptionType.PUT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asian options pinned by their limits: one fixing IS vanilla
 * Black-Scholes (exact), zero vol IS discounted intrinsic on the forward
 * average (hand computation), AM-GM orders arithmetic above geometric,
 * and averaging cheapens the call versus its vanilla cousin.
 */
class AsianOptionTest {

    private static final double S = 100, R = 0.05, Q = 0.01, VOL = 0.25, T = 0.75;

    @Test
    void singleFixingDegeneratesToVanillaBlackScholesExactly() {
        // n = 1: the "average" is the terminal price; both moments collapse
        // to the vanilla lognormal, so both pricers must equal BS to
        // machine precision — no tolerance games.
        for (double k : new double[]{85, 100, 115}) {
            double callBs = BlackScholes.price(CALL, S, k, R, Q, VOL, T);
            double putBs = BlackScholes.price(PUT, S, k, R, Q, VOL, T);
            assertEquals(callBs, AsianOption.geometricPrice(CALL, S, k, R, Q, VOL, T, 1), 1e-12);
            assertEquals(putBs, AsianOption.geometricPrice(PUT, S, k, R, Q, VOL, T, 1), 1e-12);
            assertEquals(callBs, AsianOption.arithmeticPrice(CALL, S, k, R, Q, VOL, T, 1), 1e-12);
            assertEquals(putBs, AsianOption.arithmeticPrice(PUT, S, k, R, Q, VOL, T, 1), 1e-12);
        }
    }

    @Test
    void averagingCheapensTheCallVersusVanilla() {
        // The average has less variance AND (with r > q) a lower forward
        // than the terminal price: the ATM Asian call must be worth less.
        double vanilla = BlackScholes.price(CALL, S, 100, R, Q, VOL, T);
        double geo = AsianOption.geometricPrice(CALL, S, 100, R, Q, VOL, T, 12);
        double arith = AsianOption.arithmeticPrice(CALL, S, 100, R, Q, VOL, T, 12);
        assertTrue(geo < vanilla, "geometric " + geo + " vs vanilla " + vanilla);
        assertTrue(arith < vanilla, "arithmetic " + arith + " vs vanilla " + vanilla);
    }

    @Test
    void arithmeticCallDominatesGeometricByAmGm() {
        // A >= G pathwise, so the arithmetic call is worth at least the
        // geometric call at every strike and fixing count.
        for (int n : new int[]{2, 4, 12, 52}) {
            for (double k : new double[]{85, 100, 115}) {
                double geo = AsianOption.geometricPrice(CALL, S, k, R, Q, VOL, T, n);
                double arith = AsianOption.arithmeticPrice(CALL, S, k, R, Q, VOL, T, n);
                assertTrue(arith >= geo - 1e-12,
                        "n=" + n + " K=" + k + ": arith " + arith + " < geo " + geo);
            }
        }
        // And strictly greater away from the n = 1 degenerate case.
        assertTrue(AsianOption.arithmeticPrice(CALL, S, 100, R, Q, VOL, T, 12)
                > AsianOption.geometricPrice(CALL, S, 100, R, Q, VOL, T, 12));
    }

    @Test
    void zeroVolPaysDiscountedIntrinsicOnTheForwardAverage() {
        // Deterministic world, n = 4 quarterly fixings over one year:
        // the averages are known numbers, prices are exact hand sums.
        int n = 4;
        double r = 0.05, t = 1.0, k = 90;
        double arithAvg = 0, geoLogAvg = 0;
        for (int i = 1; i <= n; i++) {
            arithAvg += S * Math.exp(r * i * t / n);
            geoLogAvg += Math.log(S) + r * i * t / n;
        }
        arithAvg /= n;
        double geoAvg = Math.exp(geoLogAvg / n);
        double df = Math.exp(-r * t);
        assertEquals(df * (arithAvg - k),
                AsianOption.arithmeticPrice(CALL, S, k, r, 0, 0, t, n), 1e-10);
        assertEquals(df * (geoAvg - k),
                AsianOption.geometricPrice(CALL, S, k, r, 0, 0, t, n), 1e-10);
        // Deep OTM call in a deterministic world is worth exactly zero.
        assertEquals(0, AsianOption.arithmeticPrice(CALL, S, 200, r, 0, 0, t, n), 0.0);
        assertEquals(0, AsianOption.geometricPrice(CALL, S, 200, r, 0, 0, t, n), 0.0);
        // Zero-vol put: discounted (K - average)+ on the same numbers.
        assertEquals(df * (200 - arithAvg),
                AsianOption.arithmeticPrice(PUT, S, 200, r, 0, 0, t, n), 1e-10);
    }

    @Test
    void moreFixingsCutTheGeometricVarianceTowardTheContinuousThird() {
        // Var[ln G] scales by (n+1)(2n+1)/(6n^2): 1 at n=1 down to 1/3 as
        // n grows — so the ATM price must fall monotonically in n.
        double prev = Double.POSITIVE_INFINITY;
        for (int n : new int[]{1, 2, 4, 12, 52, 252}) {
            double price = AsianOption.geometricPrice(CALL, S, 100, 0, 0, VOL, T, n);
            assertTrue(price < prev, "n=" + n + " did not cheapen the call");
            prev = price;
        }
        // And the n = 252 price sits just above the continuous-averaging
        // limit (drift factor 1/2, variance factor 1/3), recomputed here
        // from scratch with the same lognormal-Black arithmetic.
        double meanLog = Math.log(S) - 0.5 * VOL * VOL * T * 0.5;
        double varLog = VOL * VOL * T / 3;
        double f = Math.exp(meanLog + 0.5 * varLog);
        double sd = Math.sqrt(varLog);
        double d1 = (Math.log(f / 100) + 0.5 * varLog) / sd;
        double floor = f * MathUtils.normCdf(d1) - 100 * MathUtils.normCdf(d1 - sd);
        assertTrue(prev > floor, "n=252 " + prev + " vs continuous " + floor);
        assertTrue(prev < floor + 0.05, "n=252 should be within pennies of the limit");
    }

    @Test
    void gatesRefuseNonsense() {
        assertThrows(IllegalArgumentException.class,
                () -> AsianOption.geometricPrice(CALL, 0, 100, R, Q, VOL, T, 4));
        assertThrows(IllegalArgumentException.class,
                () -> AsianOption.geometricPrice(CALL, S, Double.NaN, R, Q, VOL, T, 4));
        assertThrows(IllegalArgumentException.class,
                () -> AsianOption.geometricPrice(CALL, S, 100, Double.NaN, Q, VOL, T, 4));
        assertThrows(IllegalArgumentException.class,
                () -> AsianOption.geometricPrice(CALL, S, 100, R, Q, -0.1, T, 4));
        assertThrows(IllegalArgumentException.class,
                () -> AsianOption.geometricPrice(CALL, S, 100, R, Q, VOL, 0, 4));
        assertThrows(IllegalArgumentException.class,
                () -> AsianOption.geometricPrice(CALL, S, 100, R, Q, VOL, T, 0));
        assertThrows(IllegalArgumentException.class,
                () -> AsianOption.arithmeticPrice(CALL, S, 100, R, Q, VOL,
                        Double.POSITIVE_INFINITY, 4));
        assertThrows(IllegalArgumentException.class,
                () -> AsianOption.arithmeticPrice(CALL, -1, 100, R, Q, VOL, T, 4));
    }
}
