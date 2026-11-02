package com.quantfinlib.credit;

import com.quantfinlib.rates.CurveInterpolation;
import com.quantfinlib.rates.DiscountCurve;
import com.quantfinlib.util.MathUtils;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Foreign-currency swaps and the stochastic projection basis (2.3): exact
 * reduction to the 2.2 engine, martingale checks of discounted values, and
 * a Black closed form for the stochastic-basis FRA exposure (own tests).
 */
class MultiCurrencySwapBasisTest {

    private static final LocalDate D0 = LocalDate.of(2026, 11, 2);

    private static DiscountCurve curve(double base, double slope) {
        double[] t = {0.5, 1, 2, 3, 5, 7, 10};
        double[] df = new double[t.length];
        for (int i = 0; i < t.length; i++) {
            df[i] = Math.exp(-(base + slope * Math.min(t[i], 5)) * t[i]);
        }
        return DiscountCurve.of(D0, t, df, CurveInterpolation.LOG_LINEAR_DISCOUNT);
    }

    private static final DiscountCurve OIS = curve(0.025, 0.002);
    private static final DiscountCurve IBOR = curve(0.029, 0.002);
    private static final DiscountCurve EUR = curve(0.015, 0.001);
    private static final DiscountCurve EURIBOR = curve(0.018, 0.001);

    private static MultiCurrencyExposureSimulator.Curve c(DiscountCurve d) {
        return MultiCurrencyExposureSimulator.Curve.of(d);
    }

    private static double p(DiscountCurve d, double t) {
        return t == 0 ? 1 : d.discountFactor(t);
    }

    private static MultiCurrencyExposureSimulator.Market eurMarket(double domVol, double fgnVol, double fxVol) {
        return new MultiCurrencyExposureSimulator.Market(
                new MultiCurrencyExposureSimulator.Domestic(c(OIS), 0.05, domVol),
                List.of(new MultiCurrencyExposureSimulator.Foreign(c(EUR), 0.03, fgnVol, 1.10, fxVol)),
                new double[][]{{1, 0.5, -0.2}, {0.5, 1, -0.3}, {-0.2, -0.3, 1}});
    }

    private static final double[] TIMES = {0.5, 1, 2, 2.5, 3.5};

    @Test
    void reducesToTheOldEngineExactly() {
        var mk = eurMarket(0.01, 0.012, 0.12);
        var sw = List.of(new MultiCurrencyExposureSimulator.Swap(
                new ExposureSimulator.Swap(100, 0.03, true, 0, 5, 2), c(IBOR)));
        var fw = List.of(new MultiCurrencyExposureSimulator.FxForward(0,
                new ExposureSimulator.FxForward(50, 1.12, 3)));
        ExposureProfile old = MultiCurrencyExposureSimulator.simulate(mk, sw, fw, null, TIMES, 500, 7L, 0);
        ExposureProfile neu = MultiCurrencyExposureSimulator.simulate(mk, sw, List.of(), fw, null, null,
                TIMES, 500, 7L, 0);
        var zeroBasis = new MultiCurrencyExposureSimulator.BasisFactor(0.3, 0, new double[]{0.2, 0, 0});
        ExposureProfile zb = MultiCurrencyExposureSimulator.simulate(mk, sw, List.of(), fw, zeroBasis, null,
                TIMES, 500, 7L, 0);
        for (int i = 0; i < TIMES.length; i++) {
            assertEquals(old.discountedExpectedExposure(i), neu.discountedExpectedExposure(i), 0.0);
            assertEquals(old.discountedExpectedNegativeExposure(i), neu.discountedExpectedNegativeExposure(i), 0.0);
            assertEquals(old.discountedExpectedExposure(i), zb.discountedExpectedExposure(i), 0.0);
            assertEquals(old.discountedExpectedNegativeExposure(i), zb.discountedExpectedNegativeExposure(i), 0.0);
        }
    }

    /** Forward-start foreign payer swap 3y-5y annual, value today in domestic. */
    private static double foreignV0(double notional, double k) {
        double v = 0;
        double[] d = {3, 4, 5};
        for (int j = 1; j < d.length; j++) {
            double kb = (p(EURIBOR, d[j - 1]) / p(EURIBOR, d[j])) / (p(EUR, d[j - 1]) / p(EUR, d[j]));
            v += kb * p(EUR, d[j - 1]) - p(EUR, d[j]) - k * (d[j] - d[j - 1]) * p(EUR, d[j]);
        }
        return 1.10 * notional * v;
    }

    @Test
    void foreignSwapDiscountedValueIsAMartingale() {
        var mk = eurMarket(0.01, 0.012, 0.12);
        var t = new ExposureSimulator.Swap(100, 0.02, true, 3, 5, 1);
        var r = new ExposureSimulator.Swap(100, 0.02, false, 3, 5, 1);
        int n = 40_000;
        ExposureProfile pay = MultiCurrencyExposureSimulator.simulate(mk, List.of(),
                List.of(new MultiCurrencyExposureSimulator.ForeignSwap(0, t, c(EURIBOR))), List.of(), null, null,
                TIMES, n, 11L, 0);
        ExposureProfile rec = MultiCurrencyExposureSimulator.simulate(mk, List.of(),
                List.of(new MultiCurrencyExposureSimulator.ForeignSwap(0, r, c(EURIBOR))), List.of(), null, null,
                TIMES, n, 11L, 0);
        double v0 = foreignV0(100, 0.02);
        for (int i = 0; i < TIMES.length; i++) {
            double ev = pay.discountedExpectedExposure(i) - rec.discountedExpectedExposure(i);
            double se = pay.discountedExpectedExposureStdError(i) + rec.discountedExpectedExposureStdError(i);
            assertEquals(v0, ev, 4 * se, "t=" + TIMES[i]);
            // payer and receiver see the same paths: their exposures mirror
            assertEquals(pay.discountedExpectedNegativeExposure(i), rec.discountedExpectedExposure(i),
                    1e-9 * (1 + rec.discountedExpectedExposure(i)));
        }
    }

    @Test
    void foreignSwapWithDeterministicForeignRateAndFlatFxIsExact() {
        // With sigma_f = sigma_S = 0, D_d(0,t) S(t) = S0 P_f(0,t) on every path, so
        // DEE(t) = max(V0, 0) exactly while the domestic rate stays random.
        var mk = eurMarket(0.01, 0, 0);
        var t = new ExposureSimulator.Swap(100, 0.01, true, 3, 5, 1);
        ExposureProfile pr = MultiCurrencyExposureSimulator.simulate(mk, List.of(),
                List.of(new MultiCurrencyExposureSimulator.ForeignSwap(0, t, c(EURIBOR))), List.of(), null, null,
                new double[]{1, 2.5}, 200, 3L, 0);
        double v0 = foreignV0(100, 0.01);
        assertTrue(v0 > 0);
        assertEquals(v0, pr.discountedExpectedExposure(0), 1e-9 * v0);
        assertEquals(v0, pr.discountedExpectedExposure(1), 1e-9 * v0);
    }

    private static MultiCurrencyExposureSimulator.Market ratesOnly(double vol) {
        return new MultiCurrencyExposureSimulator.Market(
                new MultiCurrencyExposureSimulator.Domestic(c(OIS), 0.05, vol), List.of(), new double[][]{{1}});
    }

    @Test
    void stochasticBasisKeepsTheForwardFair() {
        // Single-period FRA 3y-4y on IBOR; with correlated basis the discounted value
        // must stay a martingale (this checks the drift D(t), including the rho term).
        var mk = ratesOnly(0.012);
        var bf = new MultiCurrencyExposureSimulator.BasisFactor(0.3, 0.05, new double[]{0.6});
        double[] times = {1, 2.5, 3.5};
        int n = 40_000;
        var pay = new ExposureSimulator.Swap(100, 0.03, true, 3, 4, 1);
        var rec = new ExposureSimulator.Swap(100, 0.03, false, 3, 4, 1);
        ExposureProfile a = MultiCurrencyExposureSimulator.simulate(mk,
                List.of(new MultiCurrencyExposureSimulator.Swap(pay, c(IBOR))), List.of(), List.of(), bf, null,
                times, n, 21L, 0);
        ExposureProfile b = MultiCurrencyExposureSimulator.simulate(mk,
                List.of(new MultiCurrencyExposureSimulator.Swap(rec, c(IBOR))), List.of(), List.of(), bf, null,
                times, n, 21L, 0);
        double v0 = 100 * (p(IBOR, 3) / p(IBOR, 4) * p(OIS, 4) - p(OIS, 4) - 0.03 * p(OIS, 4));
        for (int i = 0; i < times.length; i++) {
            double ev = a.discountedExpectedExposure(i) - b.discountedExpectedExposure(i);
            double se = a.discountedExpectedExposureStdError(i) + b.discountedExpectedExposureStdError(i);
            assertEquals(v0, ev, 4 * se, "t=" + times[i]);
        }
    }

    @Test
    void stochasticBasisFraExposureMatchesBlack() {
        // Deterministic domestic rates: V(t) = N [k0 m(t) P(t,S) - (1 + K) P(t,T)], m lognormal,
        // var ln m(t) = sigma_b^2 e^{-2a(S-t)} (1 - e^{-2at}) / (2a). Independent closed form.
        double a = 0.4, sb = 0.03, s = 3, e = 4, k = 0.034, tt = 2;
        var mk = ratesOnly(0);
        var bf = new MultiCurrencyExposureSimulator.BasisFactor(a, sb, new double[]{0});
        var sw = new ExposureSimulator.Swap(100, k, true, s, e, 1);
        int n = 40_000;
        ExposureProfile pr = MultiCurrencyExposureSimulator.simulate(mk,
                List.of(new MultiCurrencyExposureSimulator.Swap(sw, c(IBOR))), List.of(), List.of(), bf, null,
                new double[]{tt}, n, 5L, 0);
        double k0 = (p(IBOR, s) / p(IBOR, e)) / (p(OIS, s) / p(OIS, e));
        double fwdA = 100 * k0 * p(OIS, s);          // discounted to 0
        double fwdB = 100 * (1 + k) * p(OIS, e);
        double v = sb * sb * Math.exp(-2 * a * (s - tt)) * (1 - Math.exp(-2 * a * tt)) / (2 * a);
        double d1 = (Math.log(fwdA / fwdB) + 0.5 * v) / Math.sqrt(v), d2 = d1 - Math.sqrt(v);
        double black = fwdA * MathUtils.normCdf(d1) - fwdB * MathUtils.normCdf(d2);
        assertEquals(black, pr.discountedExpectedExposure(0), 4 * pr.discountedExpectedExposureStdError(0));
        assertTrue(black > 0.05, "test should be informative, black=" + black);
    }

    @Test
    void rejectsBadBasisAndPairs() {
        var mk = eurMarket(0.01, 0.012, 0.12);
        var sw = new ExposureSimulator.Swap(100, 0.03, true, 0, 5, 1);
        assertThrows(IllegalArgumentException.class, () -> MultiCurrencyExposureSimulator.simulate(mk, List.of(),
                List.of(new MultiCurrencyExposureSimulator.ForeignSwap(1, sw, null)), List.of(), null, null,
                TIMES, 10, 1L, 0));
        assertThrows(IllegalArgumentException.class, () -> MultiCurrencyExposureSimulator.simulate(mk,
                List.of(new MultiCurrencyExposureSimulator.Swap(sw, c(IBOR))), List.of(), List.of(),
                new MultiCurrencyExposureSimulator.BasisFactor(0.3, 0.01, new double[]{0.5}), null,
                TIMES, 10, 1L, 0));
        assertThrows(IllegalArgumentException.class, () -> MultiCurrencyExposureSimulator.simulate(mk,
                List.of(new MultiCurrencyExposureSimulator.Swap(sw, c(IBOR))), List.of(), List.of(),
                new MultiCurrencyExposureSimulator.BasisFactor(0.3, 0.01, new double[]{1, 1, 1}), null,
                TIMES, 10, 1L, 0));
    }
}
