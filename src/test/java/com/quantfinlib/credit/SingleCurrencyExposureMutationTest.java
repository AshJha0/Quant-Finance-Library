package com.quantfinlib.credit;

import com.quantfinlib.rates.YieldCurve;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Single-currency behaviour of {@link MultiCurrencyExposureSimulator} (the
 * 2.x {@code ExposureSimulator} case, folded in 3.0) that
 * SingleCurrencyExposureTest does not pin: parameter boundaries of the term
 * records, the swap schedule, the CSA target function, the martingale
 * property of discounted values (E[D(t) V(t)] = V(0) when no cash flow falls
 * in (0, t]), valuation on a maturity date and inside the first accrual
 * period, and the moments of the wrong-way drivers. All expected values are
 * own closed forms; Monte Carlo checks use four standard errors.
 */
class SingleCurrencyExposureMutationTest {

    private static final double Z = 0.03;

    private static YieldCurve flat(double z) {
        return YieldCurve.ofZeroRates(new double[]{1, 2, 5, 10}, new double[]{z, z, z, z});
    }

    private static double df(double z, double t) {
        return Math.exp(-z * t);
    }

    @Test
    void swapBoundariesAndSchedule() {
        assertDoesNotThrow(() -> new MultiCurrencyExposureSimulator.SwapTerms(1, 0.03, true, 0, 1e-6, 1));
        assertThrows(IllegalArgumentException.class, () -> new MultiCurrencyExposureSimulator.SwapTerms(0, 0.03, true, 0, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new MultiCurrencyExposureSimulator.SwapTerms(Double.POSITIVE_INFINITY, 0.03, true, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new MultiCurrencyExposureSimulator.SwapTerms(1, Double.NaN, true, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new MultiCurrencyExposureSimulator.SwapTerms(1, 0.03, true, -1e-12, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new MultiCurrencyExposureSimulator.SwapTerms(1, 0.03, true, 1, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new MultiCurrencyExposureSimulator.SwapTerms(1, 0.03, true, 0, Double.POSITIVE_INFINITY, 1));
        assertThrows(IllegalArgumentException.class, () -> new MultiCurrencyExposureSimulator.SwapTerms(1, 0.03, true, 0, 1, 0));

        // Short last period.
        assertArrayEquals(new double[]{0.5, 1.0, 1.5, 2.0, 2.25},
                new MultiCurrencyExposureSimulator.SwapTerms(1, 0.03, true, 0.5, 2.25, 2).dates(), 1e-15);
        // Exact multiple: no zero-length stub, even when the division is inexact
        // in floating point ((1.3 - 0.1) / 0.1 = 11.999...).
        double[] d = new MultiCurrencyExposureSimulator.SwapTerms(1, 0.03, true, 0.1, 1.3, 10).dates();
        assertEquals(13, d.length);
        assertEquals(0.1, d[0], 0);
        assertEquals(1.3, d[12], 0);
        for (int j = 1; j < d.length; j++) {
            assertEquals(0.1, d[j] - d[j - 1], 1e-12, "period " + j);
        }
        double[] q = new MultiCurrencyExposureSimulator.SwapTerms(1, 0.03, true, 0, 3, 4).dates();
        assertEquals(13, q.length);
        assertEquals(0.25, q[1], 0);
        assertEquals(2.75, q[11], 0);
    }

    @Test
    void forwardBoundaries() {
        assertDoesNotThrow(() -> new MultiCurrencyExposureSimulator.FxForwardTerms(-1, 1e-9, 1e-9));
        assertThrows(IllegalArgumentException.class, () -> new MultiCurrencyExposureSimulator.FxForwardTerms(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new MultiCurrencyExposureSimulator.FxForwardTerms(Double.NaN, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new MultiCurrencyExposureSimulator.FxForwardTerms(1, 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new MultiCurrencyExposureSimulator.FxForwardTerms(1, Double.POSITIVE_INFINITY, 1));
        assertThrows(IllegalArgumentException.class, () -> new MultiCurrencyExposureSimulator.FxForwardTerms(1, 1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new MultiCurrencyExposureSimulator.FxForwardTerms(1, 1, Double.POSITIVE_INFINITY));
    }

    @Test
    void csaBoundariesAndTarget() {
        assertDoesNotThrow(() -> new MultiCurrencyExposureSimulator.Csa(0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new MultiCurrencyExposureSimulator.Csa(-1e-12, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new MultiCurrencyExposureSimulator.Csa(0, -1e-12, 0));
        assertThrows(IllegalArgumentException.class, () -> new MultiCurrencyExposureSimulator.Csa(0, 0, -1e-12));
        assertThrows(IllegalArgumentException.class, () -> new MultiCurrencyExposureSimulator.Csa(Double.NaN, 0, 0));
        // Each term finite but the sum overflows: the finiteness check is on the sum.
        assertThrows(IllegalArgumentException.class,
                () -> new MultiCurrencyExposureSimulator.Csa(Double.MAX_VALUE, Double.MAX_VALUE, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new MultiCurrencyExposureSimulator.Csa(0, Double.MAX_VALUE, Double.MAX_VALUE));

        var csa = new MultiCurrencyExposureSimulator.Csa(2, 0, 0);
        assertEquals(3, csa.target(5), 0);
        assertEquals(-3, csa.target(-5), 0);
        assertEquals(0, csa.target(1.5), 0);
        assertEquals(0, csa.target(-1.5), 0);
        assertEquals(0, csa.target(2), 0);
        assertEquals(0, csa.target(-2), 0);
    }

    @Test
    void simulateAcceptsTwoPathsAndRejectsOne() {
        var mk = LegacyExposure.Market.ratesOnly(flat(Z), 0.1, 0.01);
        var sw = List.of(new MultiCurrencyExposureSimulator.SwapTerms(1, 0.03, true, 0, 2, 1));
        assertEquals(2, LegacyExposure.simulate(mk, sw, List.of(), null, new double[]{1}, 2, 1).paths());
        assertThrows(IllegalArgumentException.class,
                () -> LegacyExposure.simulate(mk, sw, List.of(), null, new double[]{1}, 1, 1));
    }

    /** E[D(t)(V+ - V-)] from the profile. */
    private static double discountedMean(ExposureProfile p, int i) {
        return p.discountedExpectedExposure(i) - p.discountedExpectedNegativeExposure(i);
    }

    @Test
    void discountedFxForwardValueIsAMartingale() {
        // Correlated stochastic rates and FX: E[D(t) V(t)] = V(0) =
        // N (S0 Pf(T) - K Pd(T)) at every date before maturity.
        double zf = 0.01, s0 = 1.1, k = 1.05, n = 100, tm = 3;
        var mk = new LegacyExposure.Market(flat(Z), 0.08, 0.015, flat(zf), s0, 0.12, -0.4);
        var fw = List.of(new MultiCurrencyExposureSimulator.FxForwardTerms(n, k, tm));
        double[] times = {0.5, 1.5, 2.5};
        var p = LegacyExposure.simulate(mk, List.of(), fw, null, times, 20000, 7);
        double v0 = n * (s0 * df(zf, tm) - k * df(Z, tm));
        for (int i = 0; i < times.length; i++) {
            double tol = 8 * p.discountedExpectedExposureStdError(i) + 0.05;
            assertEquals(v0, discountedMean(p, i), tol, "t=" + times[i]);
        }
    }

    @Test
    void discountedForwardStartSwapValueIsAMartingale() {
        // A swap starting at 2y: no cash flow before 2y, so E[D(t)V(t)] = V(0)
        // for t < 2, with V(0) = N (P(2) - P(5) - K sum P(T_j)).
        double n = 100, k = 0.035;
        var mk = LegacyExposure.Market.ratesOnly(flat(Z), 0.05, 0.02);
        var sw = List.of(new MultiCurrencyExposureSimulator.SwapTerms(n, k, true, 2, 5, 1));
        double[] times = {0.5, 1, 1.9};
        var p = LegacyExposure.simulate(mk, sw, List.of(), null, times, 20000, 11);
        double v0 = n * (df(Z, 2) - df(Z, 5) - k * (df(Z, 3) + df(Z, 4) + df(Z, 5)));
        for (int i = 0; i < times.length; i++) {
            double se = p.discountedExpectedExposureStdError(i);
            assertEquals(v0, discountedMean(p, i), 8 * se + 0.02, "t=" + times[i]);
        }
        // Rates up help the payer: positive and negative exposure both present.
        assertTrue(p.discountedExpectedExposure(2) > 0);
        assertTrue(p.discountedExpectedNegativeExposure(2) > 0);
    }

    @Test
    void valueInsideFirstPeriodUsesTheTimeZeroReset() {
        // Spot-start annual swap observed at 0.5y, inside its first period.
        // The coupon fixed at 0 is known, so with zero rate vol the value is
        // deterministic: P(0,1)/P(0,0.5) etc. on the flat curve.
        double n = 100, k = 0.02;
        var mk = LegacyExposure.Market.ratesOnly(flat(Z), 0.05, 0);
        var sw = List.of(new MultiCurrencyExposureSimulator.SwapTerms(n, k, true, 0, 3, 1));
        double t = 0.5;
        var p = LegacyExposure.simulate(mk, sw, List.of(), null, new double[]{t}, 4, 3);
        // Float leg = (1/P(0,1)) P(t,1) - P(t,3); fixed = K (P(t,1) + P(t,2) + P(t,3)).
        double pt1 = df(Z, 1 - t), pt2 = df(Z, 2 - t), pt3 = df(Z, 3 - t);
        double flt = pt1 / df(Z, 1) - pt3;
        double v = n * (flt - k * (pt1 + pt2 + pt3));
        assertTrue(v > 0);
        assertEquals(v, p.expectedExposure(0), 1e-9);
        assertEquals(df(Z, t) * v, p.discountedExpectedExposure(0), 1e-9);
    }

    @Test
    void exposureIsZeroOnAndAfterMaturity() {
        var mk = new LegacyExposure.Market(flat(Z), 0.05, 0.01, flat(0.01), 1.1, 0.1, 0);
        var sw = List.of(new MultiCurrencyExposureSimulator.SwapTerms(100, 0.02, true, 0, 2, 1));
        var fw = List.of(new MultiCurrencyExposureSimulator.FxForwardTerms(100, 1.0, 1));
        var p = LegacyExposure.simulate(mk, sw, fw, null, new double[]{0.5, 1, 2, 3}, 200, 5);
        assertTrue(p.expectedExposure(0) > 0);
        assertTrue(p.expectedExposure(1) > 0, "swap still alive at 1y");
        assertEquals(0, p.expectedExposure(2), 0);
        assertEquals(0, p.expectedNegativeExposure(2), 0);
        assertEquals(0, p.expectedExposure(3), 0);
    }

    @Test
    void wrongWayDriversAreStandardNormalWithTheModelCorrelation() {
        double rho = 0.6;
        var mk = new LegacyExposure.Market(flat(Z), 0.1, 0.01, flat(0.01), 1.1, 0.15, rho);
        var fw = List.of(new MultiCurrencyExposureSimulator.FxForwardTerms(100, 1.1, 5));
        double[] times = {0.25, 2};
        int np = 20000;
        var p = LegacyExposure.simulate(mk, List.of(), fw, null, times, np, 13);
        for (int i = 0; i < times.length; i++) {
            double mr = 0, mf = 0, vr = 0, vf = 0, cov = 0;
            for (int q = 0; q < np; q++) {
                double r = p.driver(q, i, ExposureProfile.Factor.RATES);
                double f = p.driver(q, i, ExposureProfile.Factor.FX);
                mr += r;
                mf += f;
                vr += r * r;
                vf += f * f;
                cov += r * f;
            }
            mr /= np;
            mf /= np;
            vr = vr / np - mr * mr;
            vf = vf / np - mf * mf;
            cov = cov / np - mr * mf;
            assertEquals(0, mr, 0.03);
            assertEquals(0, mf, 0.03);
            assertEquals(1, vr, 0.04, "rates driver variance at t=" + times[i]);
            assertEquals(1, vf, 0.04, "fx driver variance at t=" + times[i]);
            assertEquals(rho, cov / Math.sqrt(vr * vf), 0.03);
        }
    }

    @Test
    void collateralisedDriverIsTheMarginPeriodIncrement() {
        // Under a CSA the driver is the Brownian increment over the MPR,
        // standardised by sqrt(MPR): still unit variance.
        var mk = LegacyExposure.Market.ratesOnly(flat(Z), 0.1, 0.01);
        var sw = List.of(new MultiCurrencyExposureSimulator.SwapTerms(100, 0.03, true, 0, 5, 1));
        var csa = new MultiCurrencyExposureSimulator.Csa(0, 0, 0.04);
        int np = 20000;
        var p = LegacyExposure.simulate(mk, sw, List.of(), csa, new double[]{1, 2}, np, 17);
        double m = 0, v = 0;
        for (int q = 0; q < np; q++) {
            double r = p.driver(q, 1, ExposureProfile.Factor.RATES);
            m += r;
            v += r * r;
            assertEquals(0, p.driver(q, 1, ExposureProfile.Factor.FX), 0);
        }
        m /= np;
        assertEquals(0, m, 0.03);
        assertEquals(1, v / np - m * m, 0.04);
        // Zero threshold, zero MTA: exposure is only the move over the MPR,
        // much smaller than the uncollateralised exposure.
        var un = LegacyExposure.simulate(mk, sw, List.of(), null, new double[]{1, 2}, np, 17);
        assertTrue(p.expectedExposure(1) < 0.3 * un.expectedExposure(1));
    }
}
