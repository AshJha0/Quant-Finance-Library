package com.quantfinlib.volatility;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * Value semantics of the hand-written result classes in this package
 * (equals, hashCode, toString, defensive copies) and a few small formulas
 * checked by hand. The hash values are pinned to the documented fold
 * {@code h = 31 h + field} starting from 0, so a change to the fold is caught.
 */
class VolatilityValueContractsTest {

    /** The fold used by every hand-written hashCode here: h = 0, then h = 31 h + part. */
    static int fold(int... parts) {
        int h = 0;
        for (int p : parts) {
            h = 31 * h + p;
        }
        return h;
    }

    private static void assertValueContract(Object a, Object same, Object... different) {
        assertEquals(a, a);
        assertEquals(a, same);
        assertEquals(same, a);
        assertEquals(a.hashCode(), same.hashCode());
        assertEquals(a.toString(), same.toString());
        assertFalse(a.equals(null));
        assertFalse(a.equals("not a value"));
        for (Object d : different) {
            assertNotEquals(a, d, d.toString());
            assertNotEquals(a.hashCode(), d.hashCode(), d.toString());
        }
    }

    private static final double[] SE = {0.1, 0.2, 0.3};
    private static final double[] RSE = {0.4, 0.5, 0.6};

    @Test
    void garchFitValueSemantics() {
        Garch11.Params p = new Garch11.Params(1e-6, 0.08, 0.9, -100);
        Garch11.Fit a = new Garch11.Fit(p, Innovation.STUDENT_T, 6.0, SE, RSE);
        Garch11.Fit b = new Garch11.Fit(p, Innovation.STUDENT_T, 6.0, SE.clone(), RSE.clone());
        assertValueContract(a, b,
                new Garch11.Fit(new Garch11.Params(2e-6, 0.08, 0.9, -100), Innovation.STUDENT_T, 6.0, SE, RSE),
                new Garch11.Fit(p, Innovation.GAUSSIAN, 6.0, SE, RSE),
                new Garch11.Fit(p, Innovation.STUDENT_T, 7.0, SE, RSE),
                new Garch11.Fit(p, Innovation.STUDENT_T, 6.0, RSE, RSE),
                new Garch11.Fit(p, Innovation.STUDENT_T, 6.0, SE, SE));
        assertEquals(fold(p.hashCode(), Innovation.STUDENT_T.hashCode(), Double.hashCode(6.0),
                Arrays.hashCode(SE), Arrays.hashCode(RSE)), a.hashCode());
        assertEquals("Fit[params=" + p + ", innovation=STUDENT_T, nu=6.0, standardErrors="
                + Arrays.toString(SE) + ", robustStandardErrors=" + Arrays.toString(RSE) + "]", a.toString());
        assertEquals(Innovation.STUDENT_T, a.innovation());
        assertEquals(6.0, a.nu());
        assertEquals(3, a.standardErrorsLength());
        assertEquals(3, a.robustStandardErrorsLength());
        assertEquals(0.2, a.standardErrors(1));
        assertEquals(0.6, a.robustStandardErrors(2));
        assertArrayEquals(SE, a.standardErrorsCopy());
        assertArrayEquals(RSE, a.robustStandardErrorsCopy());
        assertNotSame(a.standardErrorsCopy(), a.standardErrorsCopy());
        double[] src = SE.clone();
        Garch11.Fit c = new Garch11.Fit(p, Innovation.GAUSSIAN, Double.NaN, src, null);
        src[0] = 99;
        assertEquals(0.1, c.standardErrors(0), "constructor copies its arrays");
        c.standardErrorsCopy()[1] = 99;
        assertEquals(0.2, c.standardErrors(1), "copy accessor hands out a fresh array");
        assertEquals(null, c.robustStandardErrorsCopy());
        assertEquals(c, new Garch11.Fit(p, Innovation.GAUSSIAN, Double.NaN, SE, null), "NaN nu compares equal");
    }

    @Test
    void gjrFitValueSemantics() {
        GjrGarch11.Params p = new GjrGarch11.Params(1e-6, 0.03, 0.1, 0.88, -50);
        GjrGarch11.Fit a = new GjrGarch11.Fit(p, Innovation.STUDENT_T, 5.0, SE, RSE);
        assertValueContract(a, new GjrGarch11.Fit(p, Innovation.STUDENT_T, 5.0, SE.clone(), RSE.clone()),
                new GjrGarch11.Fit(new GjrGarch11.Params(1e-6, 0.03, 0.2, 0.88, -50), Innovation.STUDENT_T, 5.0, SE, RSE),
                new GjrGarch11.Fit(p, Innovation.GAUSSIAN, 5.0, SE, RSE),
                new GjrGarch11.Fit(p, Innovation.STUDENT_T, 4.0, SE, RSE),
                new GjrGarch11.Fit(p, Innovation.STUDENT_T, 5.0, RSE, RSE),
                new GjrGarch11.Fit(p, Innovation.STUDENT_T, 5.0, SE, SE));
        assertEquals(fold(p.hashCode(), Innovation.STUDENT_T.hashCode(), Double.hashCode(5.0),
                Arrays.hashCode(SE), Arrays.hashCode(RSE)), a.hashCode());
        assertEquals("Fit[params=" + p + ", innovation=STUDENT_T, nu=5.0, standardErrors="
                + Arrays.toString(SE) + ", robustStandardErrors=" + Arrays.toString(RSE) + "]", a.toString());
        assertEquals(p, a.params());
        assertEquals(Innovation.STUDENT_T, a.innovation());
        assertEquals(5.0, a.nu());
        assertEquals(0.3, a.standardErrors(2));
        assertEquals(3, a.standardErrorsLength());
        assertEquals(0.4, a.robustStandardErrors(0));
        assertEquals(3, a.robustStandardErrorsLength());
        assertArrayEquals(RSE, a.robustStandardErrorsCopy());
        assertArrayEquals(SE, a.standardErrorsCopy());
        assertEquals(0.03 + 0.05 + 0.88, p.persistence(), 1e-15);
        assertEquals(1e-6 / (1 - 0.96), p.unconditionalVariance(), 1e-15);
        GjrGarch11.Fit n = new GjrGarch11.Fit(p, Innovation.GAUSSIAN, Double.NaN, null, null);
        assertEquals(null, n.standardErrorsCopy());
        assertEquals(null, n.robustStandardErrorsCopy());
    }

    @Test
    void egarchFitValueSemantics() {
        Egarch11.Params p = new Egarch11.Params(-0.2, 0.1, -0.05, 0.97, -60);
        Egarch11.Fit a = new Egarch11.Fit(p, Innovation.GAUSSIAN, Double.NaN, SE, RSE);
        assertValueContract(a, new Egarch11.Fit(p, Innovation.GAUSSIAN, Double.NaN, SE.clone(), RSE.clone()),
                new Egarch11.Fit(new Egarch11.Params(-0.3, 0.1, -0.05, 0.97, -60), Innovation.GAUSSIAN, Double.NaN, SE, RSE),
                new Egarch11.Fit(p, Innovation.STUDENT_T, Double.NaN, SE, RSE),
                new Egarch11.Fit(p, Innovation.GAUSSIAN, 8.0, SE, RSE),
                new Egarch11.Fit(p, Innovation.GAUSSIAN, Double.NaN, RSE, RSE),
                new Egarch11.Fit(p, Innovation.GAUSSIAN, Double.NaN, SE, SE));
        assertEquals(fold(p.hashCode(), Innovation.GAUSSIAN.hashCode(), Double.hashCode(Double.NaN),
                Arrays.hashCode(SE), Arrays.hashCode(RSE)), a.hashCode());
        assertEquals("Fit[params=" + p + ", innovation=GAUSSIAN, nu=NaN, standardErrors="
                + Arrays.toString(SE) + ", robustStandardErrors=" + Arrays.toString(RSE) + "]", a.toString());
        assertEquals(p, a.params());
        assertEquals(Innovation.GAUSSIAN, a.innovation());
        assertTrue(Double.isNaN(a.nu()));
        assertEquals(0.1, a.standardErrors(0));
        assertEquals(3, a.standardErrorsLength());
        assertEquals(0.5, a.robustStandardErrors(1));
        assertEquals(3, a.robustStandardErrorsLength());
        assertArrayEquals(RSE, a.robustStandardErrorsCopy());
        assertArrayEquals(SE, a.standardErrorsCopy());
        assertEquals(-0.2 / (1 - 0.97), p.unconditionalLogVariance(), 1e-12);
        Egarch11.Fit n = new Egarch11.Fit(p, Innovation.GAUSSIAN, Double.NaN, null, null);
        assertEquals(null, n.standardErrorsCopy());
        assertEquals(null, n.robustStandardErrorsCopy());
    }

    @Test
    void harFitValueSemantics() {
        HarRv.Params p = new HarRv.Params(1e-5, 0.4, 0.3, 0.2);
        double[] hac = {1e-6, 0.05, 0.06, 0.07};
        HarRv.Fit a = new HarRv.Fit(p, hac, 2e-9, 500, true);
        assertValueContract(a, new HarRv.Fit(p, hac.clone(), 2e-9, 500, true),
                new HarRv.Fit(new HarRv.Params(2e-5, 0.4, 0.3, 0.2), hac, 2e-9, 500, true),
                new HarRv.Fit(p, new double[]{1, 2, 3, 4}, 2e-9, 500, true),
                new HarRv.Fit(p, hac, 3e-9, 500, true),
                new HarRv.Fit(p, hac, 2e-9, 501, true),
                new HarRv.Fit(p, hac, 2e-9, 500, false));
        assertEquals(fold(p.hashCode(), Arrays.hashCode(hac), Double.hashCode(2e-9),
                Integer.hashCode(500), Boolean.hashCode(true)), a.hashCode());
        assertEquals("Fit[params=" + p + ", hacStandardErrors=" + Arrays.toString(hac)
                + ", residualVariance=2.0E-9, observations=500, logModel=true]", a.toString());
        assertEquals(500, a.observations());
        assertEquals(4, a.hacStandardErrorsLength());
        assertEquals(0.06, a.hacStandardErrors(2));
        assertTrue(a.logModel());
        assertEquals(2e-9, a.residualVariance());
        assertArrayEquals(hac, a.hacStandardErrorsCopy());
    }

    @Test
    void harForecastUsesLastDayWeekAndMonthMeans() {
        double[] rv = new double[30];
        for (int i = 0; i < rv.length; i++) {
            rv[i] = 1 + i; // day = 30, week mean of 26..30 = 28, month mean of 9..30 = 19.5
        }
        HarRv.Params p = new HarRv.Params(0.5, 0.1, 0.2, 0.3);
        assertEquals(0.5 + 0.1 * 30 + 0.2 * 28 + 0.3 * 19.5, HarRv.forecast(rv, p), 1e-12);
        // Each coefficient isolated, so a swapped mean or window is caught.
        assertEquals(30, HarRv.forecast(rv, new HarRv.Params(0, 1, 0, 0)), 1e-12);
        assertEquals(28, HarRv.forecast(rv, new HarRv.Params(0, 0, 1, 0)), 1e-12);
        assertEquals(19.5, HarRv.forecast(rv, new HarRv.Params(0, 0, 0, 1)), 1e-12);
        assertEquals(7, HarRv.forecast(rv, new HarRv.Params(7, 0, 0, 0)), 1e-12);
        // Floored at zero.
        assertEquals(0, HarRv.forecast(rv, new HarRv.Params(-100, 0, 0, 0)));
        // Exactly 22 observations is enough; 21 is not.
        assertEquals(22, HarRv.forecast(Arrays.copyOfRange(rv, 0, 22), new HarRv.Params(0, 1, 0, 0)), 1e-12);
        assertThrows(IllegalArgumentException.class,
                () -> HarRv.forecast(Arrays.copyOfRange(rv, 0, 21), p));
    }

    @Test
    void ewmaBiasCorrectedVariancesByHand() {
        EwmaVolatility e = new EwmaVolatility(0.5);
        double[] r = {0.1, 0.2, -0.3, 0.4};
        double[] h = e.variances(r);
        // t=1: raw = 0.5*0.01 = 0.005, mass = 0.5 -> 0.01
        // t=2: raw = 0.0025 + 0.02 = 0.0225, mass = 0.75 -> 0.03
        // t=3: raw = 0.01125 + 0.045 = 0.05625, mass = 0.875 -> 0.0642857...
        assertEquals(0.01, h[1], 1e-15);
        assertEquals(0.03, h[2], 1e-15);
        assertEquals(0.05625 / 0.875, h[3], 1e-15);
        assertEquals(h[1], h[0], 0.0, "h0 is backfilled from h1");
        // latestVol continues the same weights one step: raw = 0.028125 + 0.08, mass = 0.9375.
        double next = (0.028125 + 0.08) / 0.9375;
        assertEquals(Math.sqrt(next), e.latestVol(r), 1e-15);
        assertEquals(Math.sqrt(next) * Math.sqrt(252), e.annualizedVol(r, 252), 1e-14);
        assertEquals(Math.sqrt(next) * 2, e.annualizedVol(r, 4), 1e-14);
        double[] seeded = e.variances(r, 0.04);
        assertArrayEquals(new double[]{0.04, 0.025, 0.0325, 0.06125}, seeded, 1e-15);
        assertThrows(IllegalArgumentException.class, () -> e.variances(new double[]{0.1}));
        assertThrows(IllegalArgumentException.class, () -> e.variances(new double[]{0.1, Double.NaN}));
        assertThrows(IllegalArgumentException.class, () -> e.variances(r, 0));
        assertThrows(IllegalArgumentException.class, () -> e.variances(r, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> e.variances(r, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new EwmaVolatility(0));
        assertThrows(IllegalArgumentException.class, () -> new EwmaVolatility(1));
        assertEquals(EwmaVolatility.riskMetrics().variances(r, 1)[1], 0.94 + 0.06 * 0.01, 1e-15);
    }

    @Test
    void packagePrivateEstimateAndLeastSquaresResultValueSemantics() {
        double[] th = {1, 2};
        double[] se = {0.1, 0.2};
        double[] rse = {0.3, 0.4};
        GarchEstimation.Estimate a = new GarchEstimation.Estimate(th, -5, se, rse);
        assertValueContract(a, new GarchEstimation.Estimate(th.clone(), -5, se.clone(), rse.clone()),
                new GarchEstimation.Estimate(new double[]{1, 3}, -5, se, rse),
                new GarchEstimation.Estimate(th, -6, se, rse),
                new GarchEstimation.Estimate(th, -5, rse, rse),
                new GarchEstimation.Estimate(th, -5, se, se));
        assertEquals(fold(Arrays.hashCode(th), Double.hashCode(-5), Arrays.hashCode(se),
                Arrays.hashCode(rse)), a.hashCode());
        assertEquals("Estimate[theta=[1.0, 2.0], logLikelihood=-5.0, se=[0.1, 0.2], robustSe=[0.3, 0.4]]",
                a.toString());

        LeastSquares.Result r = new LeastSquares.Result(th, se, 0.5);
        assertValueContract(r, new LeastSquares.Result(th.clone(), se.clone(), 0.5),
                new LeastSquares.Result(se, se, 0.5),
                new LeastSquares.Result(th, th, 0.5),
                new LeastSquares.Result(th, se, 0.6));
        assertEquals(fold(Arrays.hashCode(th), Arrays.hashCode(se), Double.hashCode(0.5)), r.hashCode());
        assertEquals("Result[beta=[1.0, 2.0], hacStdErrors=[0.1, 0.2], residualVariance=0.5]", r.toString());
        assertTrue(Objects.equals(r.beta(), th));
    }
}
