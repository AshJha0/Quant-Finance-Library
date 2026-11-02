package com.quantfinlib.credit;

import com.quantfinlib.rates.CurveInterpolation;
import com.quantfinlib.rates.DiscountCurve;
import com.quantfinlib.stats.SpecialFunctions;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validation of the multi-currency records and of the correlation checks,
 * plus two behaviours the other suites do not reach: a forward whose
 * maturity is an exposure date, and the stochastic-basis drift at a large
 * basis volatility, where leaving the drift out would bias the FRA
 * exposure by many standard errors. Expected values are own closed forms
 * (the Black formula for a lognormal basis is derived in
 * MultiCurrencySwapBasisTest); Monte Carlo checks use four standard errors.
 */
class MultiCurrencyExposureSimulatorMutationTest {

    private static final LocalDate D0 = LocalDate.of(2026, 11, 2);

    private static DiscountCurve curve(double z) {
        double[] t = {0.5, 1, 2, 3, 5, 7, 10};
        double[] df = new double[t.length];
        for (int i = 0; i < t.length; i++) {
            df[i] = Math.exp(-z * t[i]);
        }
        return DiscountCurve.of(D0, t, df, CurveInterpolation.LOG_LINEAR_DISCOUNT);
    }

    private static final DiscountCurve OIS = curve(0.025);
    private static final DiscountCurve IBOR = curve(0.029);
    private static final DiscountCurve EUR = curve(0.015);

    private static MultiCurrencyExposureSimulator.Curve c(DiscountCurve d) {
        return MultiCurrencyExposureSimulator.Curve.of(d);
    }

    private static double p(DiscountCurve d, double t) {
        return t == 0 ? 1 : d.discountFactor(t);
    }

    private static MultiCurrencyExposureSimulator.Domestic dom(double vol) {
        return new MultiCurrencyExposureSimulator.Domestic(c(OIS), 0.05, vol);
    }

    private static MultiCurrencyExposureSimulator.Foreign eur() {
        return new MultiCurrencyExposureSimulator.Foreign(c(EUR), 0.03, 0.01, 1.1, 0.1);
    }

    @Test
    void domesticAndForeignValidateTheirParameters() {
        assertDoesNotThrow(() -> new MultiCurrencyExposureSimulator.Domestic(c(OIS), 1e-9, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new MultiCurrencyExposureSimulator.Domestic(c(OIS), 0, 0.01));
        assertThrows(IllegalArgumentException.class,
                () -> new MultiCurrencyExposureSimulator.Domestic(c(OIS), Double.POSITIVE_INFINITY, 0.01));
        assertThrows(IllegalArgumentException.class,
                () -> new MultiCurrencyExposureSimulator.Domestic(c(OIS), 0.05, -1e-12));
        assertThrows(IllegalArgumentException.class,
                () -> new MultiCurrencyExposureSimulator.Domestic(c(OIS), 0.05, Double.POSITIVE_INFINITY));
        assertThrows(NullPointerException.class,
                () -> new MultiCurrencyExposureSimulator.Domestic(null, 0.05, 0.01));

        assertDoesNotThrow(() -> new MultiCurrencyExposureSimulator.Foreign(c(EUR), 0.03, 0, 1e-9, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new MultiCurrencyExposureSimulator.Foreign(c(EUR), 0, 0.01, 1.1, 0.1));
        assertThrows(IllegalArgumentException.class,
                () -> new MultiCurrencyExposureSimulator.Foreign(c(EUR), 0.03, -0.01, 1.1, 0.1));
        assertThrows(IllegalArgumentException.class,
                () -> new MultiCurrencyExposureSimulator.Foreign(c(EUR), 0.03, 0.01, 0, 0.1));
        assertThrows(IllegalArgumentException.class,
                () -> new MultiCurrencyExposureSimulator.Foreign(c(EUR), 0.03, 0.01, Double.POSITIVE_INFINITY, 0.1));
        assertThrows(IllegalArgumentException.class,
                () -> new MultiCurrencyExposureSimulator.Foreign(c(EUR), 0.03, 0.01, 1.1, -1e-12));
        assertThrows(IllegalArgumentException.class,
                () -> new MultiCurrencyExposureSimulator.Foreign(c(EUR), 0.03, 0.01, 1.1, Double.POSITIVE_INFINITY));
    }

    @Test
    void basisFactorValidatesAndCopies() {
        double[] r = {1, -1, 0};
        var bf = new MultiCurrencyExposureSimulator.BasisFactor(0.3, 0, r);
        r[0] = 0.5;
        assertEquals(1, bf.correlation()[0], 0, "constructor copies");
        bf.correlation()[1] = 0.7;
        assertArrayEquals(new double[]{1, -1, 0}, bf.correlation(), 0, "accessor copies");
        assertThrows(IllegalArgumentException.class,
                () -> new MultiCurrencyExposureSimulator.BasisFactor(0, 0.01, new double[]{0}));
        assertThrows(IllegalArgumentException.class,
                () -> new MultiCurrencyExposureSimulator.BasisFactor(0.3, -0.01, new double[]{0}));
        assertThrows(IllegalArgumentException.class,
                () -> new MultiCurrencyExposureSimulator.BasisFactor(0.3, 0.01, new double[]{1.0001}));
        assertThrows(IllegalArgumentException.class,
                () -> new MultiCurrencyExposureSimulator.BasisFactor(0.3, 0.01, new double[]{-1.0001}));
        assertThrows(IllegalArgumentException.class,
                () -> new MultiCurrencyExposureSimulator.BasisFactor(0.3, 0.01, new double[]{Double.NaN}));
        assertThrows(NullPointerException.class,
                () -> new MultiCurrencyExposureSimulator.BasisFactor(0.3, 0.01, null));
    }

    @Test
    void tradeRecordsRejectNegativePairs() {
        var sw = new MultiCurrencyExposureSimulator.SwapTerms(1, 0.03, true, 0, 2, 1);
        var fw = new MultiCurrencyExposureSimulator.FxForwardTerms(1, 1, 1);
        assertDoesNotThrow(() -> new MultiCurrencyExposureSimulator.ForeignSwap(0, sw, null));
        assertDoesNotThrow(() -> new MultiCurrencyExposureSimulator.FxForward(0, fw));
        assertThrows(IllegalArgumentException.class, () -> new MultiCurrencyExposureSimulator.ForeignSwap(-1, sw, null));
        assertThrows(IllegalArgumentException.class, () -> new MultiCurrencyExposureSimulator.FxForward(-1, fw));
        assertThrows(NullPointerException.class, () -> new MultiCurrencyExposureSimulator.Swap(null, null));
    }

    @Test
    void marketChecksTheCorrelationMatrix() {
        var d = dom(0.01);
        var f = List.of(eur());
        // Rank one, all entries 1: positive semi-definite, accepted.
        assertDoesNotThrow(() -> new MultiCurrencyExposureSimulator.Market(d, f,
                new double[][]{{1, 1, 1}, {1, 1, 1}, {1, 1, 1}}));
        assertDoesNotThrow(() -> new MultiCurrencyExposureSimulator.Market(d, f,
                new double[][]{{1, -1, 0}, {-1, 1, 0}, {0, 0, 1}}));
        assertThrows(IllegalArgumentException.class, () -> new MultiCurrencyExposureSimulator.Market(d, f,
                new double[][]{{0.9, 0, 0}, {0, 1, 0}, {0, 0, 1}}), "diagonal");
        assertThrows(IllegalArgumentException.class, () -> new MultiCurrencyExposureSimulator.Market(d, f,
                new double[][]{{1, 0, 0}, {0, 1, 0}, {0, 0, 0.9}}), "last diagonal");
        assertThrows(IllegalArgumentException.class, () -> new MultiCurrencyExposureSimulator.Market(d, f,
                new double[][]{{1, 0.2, 0}, {0.3, 1, 0}, {0, 0, 1}}), "asymmetric");
        assertThrows(IllegalArgumentException.class, () -> new MultiCurrencyExposureSimulator.Market(d, f,
                new double[][]{{1, 0, 0}, {0, 1, 0.2}, {0, 0.3, 1}}), "asymmetric, last column");
        assertThrows(IllegalArgumentException.class, () -> new MultiCurrencyExposureSimulator.Market(d, f,
                new double[][]{{1, 1.5, 0}, {1.5, 1, 0}, {0, 0, 1}}), "out of range");
        assertThrows(IllegalArgumentException.class, () -> new MultiCurrencyExposureSimulator.Market(d, f,
                new double[][]{{1, 0.9, -0.9}, {0.9, 1, 0.9}, {-0.9, 0.9, 1}}), "not PSD");
        // Degenerate pivot with a non-zero remainder below it: not PSD.
        assertThrows(IllegalArgumentException.class, () -> new MultiCurrencyExposureSimulator.Market(d, f,
                new double[][]{{1, 1, 0}, {1, 1, 0.5}, {0, 0.5, 1}}), "degenerate, not PSD");
        assertThrows(IllegalArgumentException.class, () -> new MultiCurrencyExposureSimulator.Market(d, f,
                new double[][]{{1, 0}, {0, 1}}), "wrong size");
        assertThrows(IllegalArgumentException.class, () -> new MultiCurrencyExposureSimulator.Market(d, f,
                new double[][]{{1, 0, 0}, {0, 1}, {0, 0, 1}}), "ragged");

        double[][] in = {{1, 0.3, 0}, {0.3, 1, 0}, {0, 0, 1}};
        var mk = new MultiCurrencyExposureSimulator.Market(d, f, in);
        in[0][1] = 0.9;
        assertEquals(0.3, mk.correlation()[0][1], 0, "constructor copies");
        mk.correlation()[0][1] = 0.8;
        assertEquals(0.3, mk.correlation()[0][1], 0, "accessor copies");
    }

    @Test
    void positiveSemiDefiniteAndCholeskyOnCovariances() {
        // Non-unit pivots: [[4, 2], [2, 1]] = (2, 1)(2, 1)^T is PSD of rank one.
        assertTrue(MultiCurrencyExposureSimulator.positiveSemiDefinite(new double[][]{{4, 2}, {2, 1}}));
        assertTrue(MultiCurrencyExposureSimulator.positiveSemiDefinite(new double[][]{{4, 2}, {2, 2}}));
        assertFalse(MultiCurrencyExposureSimulator.positiveSemiDefinite(new double[][]{{4, 2}, {2, 0.9}}));
        assertTrue(MultiCurrencyExposureSimulator.positiveSemiDefinite(
                new double[][]{{4, 2, 2}, {2, 1, 1}, {2, 1, 3}}));
        assertFalse(MultiCurrencyExposureSimulator.positiveSemiDefinite(
                new double[][]{{4, 2, 2}, {2, 1, 1.5}, {2, 1.5, 3}}));

        double[][] l = MultiCurrencyExposureSimulator.cholesky(new double[][]{{4, 2, 2}, {2, 2, 3}, {2, 3, 6}});
        // Hand factor: L = [[2,0,0],[1,1,0],[1,2,1]].
        assertArrayEquals(new double[]{2, 0, 0}, l[0], 1e-15);
        assertArrayEquals(new double[]{1, 1, 0}, l[1], 1e-15);
        assertArrayEquals(new double[]{1, 2, 1}, l[2], 1e-15);
        // Zero pivot gives a zero column.
        double[][] z = MultiCurrencyExposureSimulator.cholesky(new double[][]{{1, 1, 0}, {1, 1, 0}, {0, 0, 4}});
        assertArrayEquals(new double[]{1, 0, 0}, z[1], 1e-15);
        assertArrayEquals(new double[]{0, 0, 2}, z[2], 1e-15);
    }

    @Test
    void simulateBoundaries() {
        var mk = new MultiCurrencyExposureSimulator.Market(dom(0.01), List.of(eur()),
                new double[][]{{1, 0, 0}, {0, 1, 0}, {0, 0, 1}});
        var fw0 = List.of(new MultiCurrencyExposureSimulator.FxForward(0, new MultiCurrencyExposureSimulator.FxForwardTerms(1, 1, 1)));
        var fw1 = List.of(new MultiCurrencyExposureSimulator.FxForward(1, new MultiCurrencyExposureSimulator.FxForwardTerms(1, 1, 1)));
        double[] t = {0.5};
        assertEquals(2, MultiCurrencyExposureSimulator.simulate(mk, List.of(), fw0, null, t, 2, 1L).paths());
        assertThrows(IllegalArgumentException.class,
                () -> MultiCurrencyExposureSimulator.simulate(mk, List.of(), fw0, null, t, 1, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> MultiCurrencyExposureSimulator.simulate(mk, List.of(), fw1, null, t, 10, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> MultiCurrencyExposureSimulator.simulate(mk, List.of(), fw0, null, t, 10, 1L, -1));
        assertThrows(IllegalArgumentException.class,
                () -> MultiCurrencyExposureSimulator.simulate(mk, List.of(), fw0, null, new double[]{0}, 10, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> MultiCurrencyExposureSimulator.simulate(mk, List.of(), fw0, null, new double[]{1, 1}, 10, 1L));
        assertThrows(IllegalArgumentException.class, () -> MultiCurrencyExposureSimulator.simulate(mk, List.of(),
                fw0, null, new double[]{Double.POSITIVE_INFINITY}, 10, 1L));
        // A wrong-way pair past the last pair is allowed and gives a zero FX driver.
        var p = MultiCurrencyExposureSimulator.simulate(mk, List.of(), fw0, null, t, 50, 1L, 1);
        for (int q = 0; q < 50; q++) {
            assertEquals(0, p.driver(q, 0, ExposureProfile.Factor.FX), 0);
        }
    }

    @Test
    void forwardOnItsMaturityDateHasNoExposure() {
        var mk = new MultiCurrencyExposureSimulator.Market(dom(0.01), List.of(eur()),
                new double[][]{{1, 0, 0}, {0, 1, 0}, {0, 0, 1}});
        var fw = List.of(new MultiCurrencyExposureSimulator.FxForward(0, new MultiCurrencyExposureSimulator.FxForwardTerms(100, 0.5, 1)));
        var sw = List.of(new MultiCurrencyExposureSimulator.Swap(new MultiCurrencyExposureSimulator.SwapTerms(100, 0.01, true, 0, 2, 1),
                null));
        var p = MultiCurrencyExposureSimulator.simulate(mk, sw, fw, null, new double[]{0.5, 1, 2}, 200, 3L);
        assertTrue(p.expectedExposure(0) > 50, "deep in-the-money forward before maturity");
        assertTrue(p.expectedExposure(1) < 10, "on its maturity only the swap remains");
        assertEquals(0, p.expectedExposure(2), 0);
        assertEquals(0, p.expectedNegativeExposure(2), 0);
    }

    @Test
    void wrongWayDriversHaveUnitVarianceAndModelCorrelation() {
        double rho = -0.5;
        var mk = new MultiCurrencyExposureSimulator.Market(dom(0.01), List.of(eur()),
                new double[][]{{1, 0, rho}, {0, 1, 0}, {rho, 0, 1}});
        var fw = List.of(new MultiCurrencyExposureSimulator.FxForward(0, new MultiCurrencyExposureSimulator.FxForwardTerms(100, 1.1, 5)));
        int np = 20000;
        var p = MultiCurrencyExposureSimulator.simulate(mk, List.of(), fw, null, new double[]{0.25, 2}, np, 9L);
        for (int i = 0; i < 2; i++) {
            double mr = 0, mf = 0, vr = 0, vf = 0, cv = 0;
            for (int q = 0; q < np; q++) {
                double r = p.driver(q, i, ExposureProfile.Factor.RATES);
                double f = p.driver(q, i, ExposureProfile.Factor.FX);
                mr += r;
                mf += f;
                vr += r * r;
                vf += f * f;
                cv += r * f;
            }
            mr /= np;
            mf /= np;
            vr = vr / np - mr * mr;
            vf = vf / np - mf * mf;
            assertEquals(0, mr, 0.03);
            assertEquals(0, mf, 0.03);
            assertEquals(1, vr, 0.04);
            assertEquals(1, vf, 0.04);
            assertEquals(rho, (cv / np - mr * mf) / Math.sqrt(vr * vf), 0.03);
        }
    }

    @Test
    void largeBasisVolFraExposureMatchesBlack() {
        // Deterministic domestic rates, lognormal basis with sigma_b = 25 %:
        // the -sigma_b^2/2 drift term is then worth about 1.5 % of the forward,
        // many standard errors, so the drift must be right for this to pass.
        double a = 0.4, sb = 0.25, s = 3, e = 4, k = 0.034, tt = 2;
        var mk = new MultiCurrencyExposureSimulator.Market(dom(0), List.of(), new double[][]{{1}});
        var bf = new MultiCurrencyExposureSimulator.BasisFactor(a, sb, new double[]{0});
        var sw = new MultiCurrencyExposureSimulator.SwapTerms(100, k, true, s, e, 1);
        int n = 40_000;
        ExposureProfile pr = MultiCurrencyExposureSimulator.simulate(mk,
                List.of(new MultiCurrencyExposureSimulator.Swap(sw, c(IBOR))), List.of(), List.of(), bf, null,
                new double[]{tt}, n, 5L, 0);
        double k0 = (p(IBOR, s) / p(IBOR, e)) / (p(OIS, s) / p(OIS, e));
        double fwdA = 100 * k0 * p(OIS, s);
        double fwdB = 100 * (1 + k) * p(OIS, e);
        double v = sb * sb * Math.exp(-2 * a * (s - tt)) * (1 - Math.exp(-2 * a * tt)) / (2 * a);
        double d1 = (Math.log(fwdA / fwdB) + 0.5 * v) / Math.sqrt(v), d2 = d1 - Math.sqrt(v);
        double black = fwdA * SpecialFunctions.normCdf(d1) - fwdB * SpecialFunctions.normCdf(d2);
        assertEquals(black, pr.discountedExpectedExposure(0), 4 * pr.discountedExpectedExposureStdError(0));
    }
}
