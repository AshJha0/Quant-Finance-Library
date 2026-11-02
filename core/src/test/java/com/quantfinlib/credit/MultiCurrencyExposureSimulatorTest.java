package com.quantfinlib.credit;

import com.quantfinlib.rates.CurveInterpolation;
import com.quantfinlib.rates.DiscountCurve;
import com.quantfinlib.rates.YieldCurve;
import com.quantfinlib.stats.SpecialFunctions;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Multi-curve, multi-currency exposure generator: it reduces to the 2.1
 * simulator, discounted values are martingales (including the foreign
 * bond seen through the spot, which tests the quanto drift), and expected
 * exposures match independent closed forms or quadrature within four
 * standard errors.
 */
class MultiCurrencyExposureSimulatorTest {

    private static final LocalDate D0 = LocalDate.of(2026, 11, 2);
    private static final double A = 0.05;
    private static final double SIG = 0.01;

    private static DiscountCurve curve(double base, double slope) {
        double[] t = {0.5, 1, 2, 3, 5, 7, 10};
        double[] df = new double[t.length];
        for (int i = 0; i < t.length; i++) {
            df[i] = Math.exp(-(base + slope * Math.min(t[i], 5)) * t[i]);
        }
        return DiscountCurve.of(D0, t, df, CurveInterpolation.LOG_LINEAR_DISCOUNT);
    }

    private static final DiscountCurve OIS = curve(0.025, 0.002);
    private static final DiscountCurve IBOR = curve(0.029, 0.002);   // about 40 bp basis
    private static final DiscountCurve EUR = curve(0.015, 0.001);
    private static final DiscountCurve JPY = curve(0.004, 0.0005);

    private static YieldCurve legacyDom() {
        return YieldCurve.ofZeroRates(new double[]{0.5, 1, 2, 3, 5, 7, 10},
                new double[]{0.025, 0.027, 0.029, 0.031, 0.033, 0.034, 0.035});
    }

    private static YieldCurve legacyFgn() {
        return YieldCurve.ofZeroRates(new double[]{1, 5, 10}, new double[]{0.01, 0.012, 0.015});
    }

    private static double p(DiscountCurve c, double t) {
        return t == 0 ? 1 : c.discountFactor(t);
    }

    private static double b(double a, double tau) {
        return (1 - Math.exp(-a * tau)) / a;
    }

    /** Two pairs: EUR (pair 0), JPY (pair 1), factors [Wd, We, Se, Wj, Sj]. */
    private static double[][] corr5(double rhoEurRateSpot, double rhoJpyRateSpot) {
        return new double[][]{
                {1.0, 0.5, -0.2, 0.3, 0.1},
                {0.5, 1.0, rhoEurRateSpot, 0.2, 0.0},
                {-0.2, rhoEurRateSpot, 1.0, 0.0, 0.3},
                {0.3, 0.2, 0.0, 1.0, rhoJpyRateSpot},
                {0.1, 0.0, 0.3, rhoJpyRateSpot, 1.0}};
    }

    private static MultiCurrencyExposureSimulator.Market twoPairMarket(double[][] corr) {
        return new MultiCurrencyExposureSimulator.Market(
                new MultiCurrencyExposureSimulator.Domestic(MultiCurrencyExposureSimulator.Curve.of(OIS), A, SIG),
                List.of(new MultiCurrencyExposureSimulator.Foreign(
                                MultiCurrencyExposureSimulator.Curve.of(EUR), 0.03, 0.012, 1.10, 0.12),
                        new MultiCurrencyExposureSimulator.Foreign(
                                MultiCurrencyExposureSimulator.Curve.of(JPY), 0.08, 0.006, 0.0068, 0.10)),
                corr);
    }

    private static MultiCurrencyExposureSimulator.Market domesticOnly() {
        return new MultiCurrencyExposureSimulator.Market(
                new MultiCurrencyExposureSimulator.Domestic(MultiCurrencyExposureSimulator.Curve.of(OIS), A, SIG),
                List.of(), new double[][]{{1}});
    }

    /** Time-0 multi-curve value of an annual payer swap from 0 to 5. */
    private static double swapV0(double k) {
        double v = 0;
        for (int j = 1; j <= 5; j++) {
            double fwd = (p(IBOR, j - 1) / p(IBOR, j) - 1);
            v += (fwd - k) * p(OIS, j);
        }
        return 100 * v;
    }

    @Test
    void multiCurveSwapDiscountedValueIsAMartingale() {
        double k = 0.034;
        MultiCurrencyExposureSimulator.SwapTerms s = new MultiCurrencyExposureSimulator.SwapTerms(100, k, true, 0, 5, 1);
        MultiCurrencyExposureSimulator.SwapTerms r = new MultiCurrencyExposureSimulator.SwapTerms(100, k, false, 0, 5, 1);
        MultiCurrencyExposureSimulator.Curve ibor = MultiCurrencyExposureSimulator.Curve.of(IBOR);
        double[] times = {0.5, 1, 1.5, 2.5, 3, 4.5};
        int n = 20_000;
        ExposureProfile pay = MultiCurrencyExposureSimulator.simulate(domesticOnly(),
                List.of(new MultiCurrencyExposureSimulator.Swap(s, ibor)), List.of(), null, times, n, 7L);
        ExposureProfile rec = MultiCurrencyExposureSimulator.simulate(domesticOnly(),
                List.of(new MultiCurrencyExposureSimulator.Swap(r, ibor)), List.of(), null, times, n, 7L);
        double v0 = swapV0(k);
        assertTrue(Math.abs(v0) > 0.5, "basis must move the value away from zero: " + v0);
        for (int i = 0; i < times.length; i++) {
            double mean = pay.discountedExpectedExposure(i) - pay.discountedExpectedNegativeExposure(i);
            double se = pay.discountedExpectedExposureStdError(i) + rec.discountedExpectedExposureStdError(i);
            // remaining value after coupons paid before t_i
            double paid = 0;
            for (int j = 1; j <= 5; j++) {
                if (j <= times[i]) {
                    paid += 100 * ((p(IBOR, j - 1) / p(IBOR, j) - 1) - k) * p(OIS, j);
                }
            }
            assertEquals(v0 - paid, mean, 4 * se + 1e-9, "t=" + times[i]);
        }
    }

    @Test
    void multiCurveSwapEeAtResetMatchesQuadrature() {
        double k = 0.034;
        MultiCurrencyExposureSimulator.SwapTerms s = new MultiCurrencyExposureSimulator.SwapTerms(100, k, true, 0, 5, 1);
        double[] times = {1, 2, 3};
        ExposureProfile prof = MultiCurrencyExposureSimulator.simulate(domesticOnly(),
                List.of(new MultiCurrencyExposureSimulator.Swap(s, MultiCurrencyExposureSimulator.Curve.of(IBOR))),
                List.of(), null, times, 20_000, 19L);
        for (int i = 0; i < times.length; i++) {
            double t = times[i];
            double expected = quadratureEe(t, k);
            assertEquals(expected, prof.discountedExpectedExposure(i),
                    4 * prof.discountedExpectedExposureStdError(i), "t=" + t);
        }
    }

    private static double vHw(double tau) {
        return SIG * SIG / (A * A) * (tau - 2 * b(A, tau) + (1 - Math.exp(-2 * A * tau)) / (2 * A));
    }

    private static double bondHw(double t, double tm, double x) {
        double lnA = 0.5 * (vHw(tm - t) - vHw(tm) + vHw(t));
        return p(OIS, tm) / p(OIS, t) * Math.exp(lnA - b(A, tm - t) * x);
    }

    /**
     * P(0,t) E^{Q_t}[V(t)^+] with x(t) Gaussian under the t-forward measure
     * (Brigo and Mercurio 2006, eq. 3.40 with T = t), trapezoid over +-8 sd.
     */
    private static double quadratureEe(double t, double k) {
        double mean = -SIG * SIG / (A * A) * (1 - Math.exp(-A * t))
                + SIG * SIG / (2 * A * A) * (1 - Math.exp(-2 * A * t));
        double sd = SIG * Math.sqrt((1 - Math.exp(-2 * A * t)) / (2 * A));
        int n = 4000;
        double lo = mean - 8 * sd, h = 16 * sd / n, sum = 0;
        for (int q = 0; q <= n; q++) {
            double x = lo + q * h;
            double v = 0;
            for (int j = (int) t + 1; j <= 5; j++) {
                double basis = (p(IBOR, j - 1) / p(IBOR, j)) / (p(OIS, j - 1) / p(OIS, j));
                v += basis * bondHw(t, j - 1, x) - (1 + k) * bondHw(t, j, x);
            }
            double w = (q == 0 || q == n) ? 0.5 : 1;
            double z = (x - mean) / sd;
            sum += w * Math.max(100 * v, 0) * Math.exp(-0.5 * z * z) / (sd * Math.sqrt(2 * Math.PI));
        }
        return p(OIS, t) * sum * h;
    }

    /** Forward-FX variance to t for maturity T, pair EUR, by Simpson's rule. */
    private static double fwdFxVariance(double t, double tm, double[][] c) {
        double ss = 0.12, sc = 0.012, ac = 0.03;
        int n = 2000;
        double h = t / n, sum = 0;
        for (int q = 0; q <= n; q++) {
            double u = q * h;
            double bc = sc * b(ac, tm - u), bd = SIG * b(A, tm - u);
            double v = ss * ss + bc * bc + bd * bd
                    - 2 * c[1][2] * ss * bc + 2 * c[0][2] * ss * bd - 2 * c[0][1] * bc * bd;
            double w = (q == 0 || q == n) ? 1 : (q % 2 == 1 ? 4 : 2);
            sum += w * v;
        }
        return sum * h / 3;
    }

    @Test
    void fxForwardEeMatchesBlackWithStochasticRates() {
        double[][] c = corr5(-0.5, 0.2);
        double tm = 5, strike = 1.15, notional = 100;
        MultiCurrencyExposureSimulator.FxForwardTerms fwd = new MultiCurrencyExposureSimulator.FxForwardTerms(notional, strike, tm);
        double[] times = {1, 2.5, 4, 4.9};
        ExposureProfile prof = MultiCurrencyExposureSimulator.simulate(twoPairMarket(c), List.of(),
                List.of(new MultiCurrencyExposureSimulator.FxForward(0, fwd)), null, times, 40_000, 23L);
        double f0 = 1.10 * p(EUR, tm) / p(OIS, tm);
        for (int i = 0; i < times.length; i++) {
            double var = fwdFxVariance(times[i], tm, c);
            double sd = Math.sqrt(var);
            double d1 = (Math.log(f0 / strike) + 0.5 * var) / sd;
            double black = notional * p(OIS, tm)
                    * (f0 * SpecialFunctions.normCdf(d1) - strike * SpecialFunctions.normCdf(d1 - sd));
            assertEquals(black, prof.discountedExpectedExposure(i),
                    4 * prof.discountedExpectedExposureStdError(i), "t=" + times[i]);
        }
    }

    @Test
    void foreignBondThroughTheSpotIsAMartingaleWithQuantoDrift() {
        // E[D_d(0,t) V(t)] = N (S0 P_f(0,T) - K P_d(0,T)) for forwards on both pairs; strong
        // rate-spot correlation makes the quanto drift matter (about 1 % of the foreign leg).
        double[][] c = corr5(-0.7, 0.6);
        double[] times = {1, 2.5, 4, 4.9};
        int n = 40_000;
        for (int pair = 0; pair < 2; pair++) {
            double s0 = pair == 0 ? 1.10 : 0.0068;
            double nf = pair == 0 ? 100 : 100 / 0.0068;
            double k = s0;
            DiscountCurve fc = pair == 0 ? EUR : JPY;
            MultiCurrencyExposureSimulator.FxForwardTerms lng = new MultiCurrencyExposureSimulator.FxForwardTerms(nf, k, 5);
            MultiCurrencyExposureSimulator.FxForwardTerms sht = new MultiCurrencyExposureSimulator.FxForwardTerms(-nf, k, 5);
            ExposureProfile a = MultiCurrencyExposureSimulator.simulate(twoPairMarket(c), List.of(),
                    List.of(new MultiCurrencyExposureSimulator.FxForward(pair, lng)), null, times, n, 31L);
            ExposureProfile b = MultiCurrencyExposureSimulator.simulate(twoPairMarket(c), List.of(),
                    List.of(new MultiCurrencyExposureSimulator.FxForward(pair, sht)), null, times, n, 31L);
            double v0 = nf * (s0 * p(fc, 5) - k * p(OIS, 5));
            for (int i = 0; i < times.length; i++) {
                double mean = a.discountedExpectedExposure(i) - a.discountedExpectedNegativeExposure(i);
                double se = a.discountedExpectedExposureStdError(i) + b.discountedExpectedExposureStdError(i);
                assertEquals(v0, mean, 4 * se, "pair " + pair + " t=" + times[i]);
            }
        }
    }

    @Test
    void wrongWayDriverFollowsTheChosenPair() {
        // JPY spot independent of EUR spot and of all EUR factors except through Wd.
        double[][] c = {
                {1, 0, 0, 0, 0},
                {0, 1, 0, 0, 0},
                {0, 0, 1, 0, 0},
                {0, 0, 0, 1, 0},
                {0, 0, 0, 0, 1}};
        MultiCurrencyExposureSimulator.FxForwardTerms eurFwd = new MultiCurrencyExposureSimulator.FxForwardTerms(100, 1.10, 3);
        MultiCurrencyExposureSimulator.FxForwardTerms jpyFwd = new MultiCurrencyExposureSimulator.FxForwardTerms(-1000, 0.0068, 3);
        double[] times = {0.5, 1, 1.5, 2, 2.5, 2.9};
        List<MultiCurrencyExposureSimulator.FxForward> trades = List.of(
                new MultiCurrencyExposureSimulator.FxForward(0, eurFwd),
                new MultiCurrencyExposureSimulator.FxForward(1, jpyFwd));
        ExposureProfile eurDriver = MultiCurrencyExposureSimulator.simulate(twoPairMarket(c), List.of(), trades,
                null, times, 20_000, 41L, 0);
        ExposureProfile jpyDriver = MultiCurrencyExposureSimulator.simulate(twoPairMarket(c), List.of(), trades,
                null, times, 20_000, 41L, 1);
        CreditCurve cp = CreditCurve.bootstrap(new int[]{5}, new double[]{0.03}, 0.4, legacyDom());
        double indep = CvaApproximator.cva(eurDriver, cp, 0.6);
        assertEquals(indep, CvaApproximator.cva(jpyDriver, cp, 0.6), 1e-12);
        double wwEur = CvaApproximator.cvaWrongWay(eurDriver, cp, 0.6, 0.5, ExposureProfile.Factor.FX);
        double wwJpy = CvaApproximator.cvaWrongWay(jpyDriver, cp, 0.6, 0.5, ExposureProfile.Factor.FX);
        // the netting set is dominated by the long EUR leg: default when EUR is strong is wrong-way
        assertTrue(wwEur > 1.2 * indep, wwEur + " vs " + indep);
        // the JPY driver is nearly orthogonal to this exposure
        assertTrue(Math.abs(wwJpy - indep) < 0.1 * indep, wwJpy + " vs " + indep);
    }

    @Test
    void rejectsBadInputs() {
        double[][] notPsd = corr5(0.99, 0.0);
        notPsd[0][1] = notPsd[1][0] = -0.99;
        notPsd[0][2] = notPsd[2][0] = 0.99;
        assertThrows(IllegalArgumentException.class, () -> twoPairMarket(notPsd));
        assertThrows(IllegalArgumentException.class, () -> twoPairMarket(new double[][]{{1}}));
        MultiCurrencyExposureSimulator.Market m = twoPairMarket(corr5(0, 0));
        MultiCurrencyExposureSimulator.FxForwardTerms f = new MultiCurrencyExposureSimulator.FxForwardTerms(1, 1, 1);
        assertThrows(IllegalArgumentException.class, () -> MultiCurrencyExposureSimulator.simulate(m, List.of(),
                List.of(new MultiCurrencyExposureSimulator.FxForward(2, f)), null, new double[]{0.5}, 10, 1L));
        assertThrows(IllegalArgumentException.class, () -> MultiCurrencyExposureSimulator.simulate(m, List.of(),
                List.of(), null, new double[]{0.5}, 10, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> new MultiCurrencyExposureSimulator.Foreign(t -> 1, 0.1, 0.01, -1, 0.1));
    }
}
