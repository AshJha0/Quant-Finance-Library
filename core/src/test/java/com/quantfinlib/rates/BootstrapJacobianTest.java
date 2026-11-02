package com.quantfinlib.rates;

import java.time.LocalDate;
import java.util.List;
import java.util.function.ToDoubleBiFunction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The implicit-function Jacobian checked against the existing
 * bump-and-rebuild risk ({@link MultiCurveRisk}) and against a finite
 * difference of the implied quotes (self-consistency, two independent
 * computations of the same derivative).
 */
class BootstrapJacobianTest {

    private static final LocalDate VAL = LocalDate.of(2026, 11, 2);
    private static final InterestRateSwap TRADE = MultiCurveTest.irs(7, 0.0361);
    private static final ToDoubleBiFunction<DiscountCurve, DiscountCurve> PV = TRADE::payerPv;

    @Test
    void bucketsMatchBumpAndRebuildOnLogLinear() {
        List<CurveInstrument> ois = MultiCurveTest.oisInstruments();
        List<CurveInstrument> proj = MultiCurveTest.projectionInstruments();
        CurveInterpolation ll = CurveInterpolation.LOG_LINEAR_DISCOUNT;
        double[] fd = MultiCurveRisk.bucketedDv01(VAL, ois, proj, ll, PV);
        double[] an = BootstrapJacobian.bucketedDv01(VAL, ois, proj, ll, PV);
        double scale = 0;
        for (double v : fd) {
            scale = Math.max(scale, Math.abs(v));
        }
        for (int k = 0; k < fd.length; k++) {
            assertEquals(fd[k], an[k], 1e-6 * scale, "bucket " + k);
        }
    }

    @Test
    void singleCurveBucketsMatchToo() {
        List<CurveInstrument> ois = MultiCurveTest.oisInstruments();
        OisSwap trade = new OisSwap(VAL.plusDays(2), VAL.plusDays(2).plusYears(6), 0.035, 1, DayCount.ACT_360,
                BusinessCalendar.weekendsOnly(), BusinessCalendar.Roll.MODIFIED_FOLLOWING);
        ToDoubleBiFunction<DiscountCurve, DiscountCurve> pv = (d, p) -> trade.payerPv(d);
        for (CurveInterpolation interp : CurveInterpolation.values()) {
            boolean ll = interp == CurveInterpolation.LOG_LINEAR_DISCOUNT;
            // Log-linear: against MultiCurveRisk's 1bp rebuild. Monotone-convex: a 1bp rebuild
            // crosses shape-zone kinks (documented in MultiCurveRisk), so compare with a
            // 1e-7 rebuild instead, which stays inside one zone.
            double[] fd = ll ? MultiCurveRisk.bucketedDv01(VAL, ois, List.of(), interp, pv)
                    : smallBumpRebuild(ois, interp, pv, 1e-7);
            double[] an = BootstrapJacobian.bucketedDv01(VAL, ois, List.of(), interp, pv);
            double scale = 0;
            for (double v : fd) {
                scale = Math.max(scale, Math.abs(v));
            }
            double tol = ll ? 1e-6 : 1e-4;
            for (int k = 0; k < fd.length; k++) {
                assertEquals(fd[k], an[k], tol * scale, interp + " bucket " + k);
            }
        }
    }

    private static double[] smallBumpRebuild(List<CurveInstrument> ois, CurveInterpolation interp,
                                             ToDoubleBiFunction<DiscountCurve, DiscountCurve> pv, double h) {
        double[] out = new double[ois.size()];
        for (int k = 0; k < out.length; k++) {
            List<CurveInstrument> up = new java.util.ArrayList<>(ois);
            List<CurveInstrument> dn = new java.util.ArrayList<>(ois);
            up.set(k, ois.get(k).withQuote(ois.get(k).quote() + h));
            dn.set(k, ois.get(k).withQuote(ois.get(k).quote() - h));
            DiscountCurve cu = MultiCurveBootstrap.bootstrap(VAL, up, interp);
            DiscountCurve cd = MultiCurveBootstrap.bootstrap(VAL, dn, interp);
            out[k] = (pv.applyAsDouble(cu, cu) - pv.applyAsDouble(cd, cd)) / (2 * h) * 1e-4;
        }
        return out;
    }

    @Test
    void jacobianMatchesFiniteDifferenceOfImpliedQuotes() {
        List<CurveInstrument> ois = MultiCurveTest.oisInstruments();
        List<CurveInstrument> proj = MultiCurveTest.projectionInstruments();
        for (CurveInterpolation interp : CurveInterpolation.values()) {
            CurvePair c = MultiCurveRisk.buildCurves(VAL, ois, proj, interp);
            double[][] jac = BootstrapJacobian.quoteJacobian(c, ois, proj);
            int nO = ois.size();
            double h = 1e-6;
            for (int j = 0; j < jac.length; j++) {
                CurvePair up = bump(c, j, nO, h);
                CurvePair dn = bump(c, j, nO, -h);
                for (int k = 0; k < jac.length; k++) {
                    CurveInstrument inst = k < nO ? ois.get(k) : proj.get(k - nO);
                    double qu = k < nO ? inst.impliedQuote(up.discount(), up.discount())
                            : inst.impliedQuote(up.discount(), up.projection());
                    double qd = k < nO ? inst.impliedQuote(dn.discount(), dn.discount())
                            : inst.impliedQuote(dn.discount(), dn.projection());
                    assertEquals((qu - qd) / (2 * h), jac[k][j], 1e-6, interp + " J[" + k + "][" + j + "]");
                }
            }
        }
    }

    @Test
    void logLinearWeightsAreExact() {
        DiscountCurve c = DiscountCurve.of(VAL, new double[] {1, 2, 4}, new double[] {0.97, 0.94, 0.88},
                CurveInterpolation.LOG_LINEAR_DISCOUNT);
        assertEquals(0, c.minusLogDfWeights(0)[0], 0);
        assertEquals(0.5, c.minusLogDfWeights(0.5)[0], 1e-15);
        double[] w = c.minusLogDfWeights(3);
        assertEquals(0.5, w[1], 1e-15);
        assertEquals(0.5, w[2], 1e-15);
        double[] x = c.minusLogDfWeights(6);
        assertEquals(2, x[2], 1e-15);
        assertEquals(-1, x[1], 1e-15);
        DiscountCurve one = DiscountCurve.of(VAL, new double[] {2}, new double[] {0.95},
                CurveInterpolation.LOG_LINEAR_DISCOUNT);
        assertEquals(1.5, one.minusLogDfWeights(3)[0], 1e-15);
    }

    @Test
    void rejectsMismatchedInstruments() {
        List<CurveInstrument> ois = MultiCurveTest.oisInstruments();
        CurvePair c = MultiCurveRisk.buildCurves(VAL, ois, List.of(), CurveInterpolation.LOG_LINEAR_DISCOUNT);
        assertThrows(IllegalArgumentException.class,
                () -> BootstrapJacobian.quoteJacobian(c, ois.subList(1, ois.size()), List.of()));
    }

    private static CurvePair bump(CurvePair c, int j, int nO, double h) {
        if (j < nO) {
            DiscountCurve d = bumpCurve(c.discount(), j, h);
            return new CurvePair(d, c.projection());
        }
        return new CurvePair(c.discount(), bumpCurve(c.projection(), j - nO, h));
    }

    private static DiscountCurve bumpCurve(DiscountCurve c, int j, double h) {
        double[] y = c.pillarMinusLogDf();
        y[j] += h;
        return DiscountCurve.ofMinusLogDf(VAL, c.pillarTimes(), y, c.interpolation());
    }
}
