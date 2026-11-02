package com.quantfinlib.rates;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.function.ToDoubleBiFunction;

/**
 * Bucketed curve risk from the analytic Jacobian of the bootstrap, instead
 * of bumping each quote and rebuilding the curves ({@link MultiCurveRisk}).
 *
 * <p>The plain idea: the bootstrap makes every instrument reprice,
 * {@code q = I(Y)}, where {@code Y} are the pillar values
 * {@code y_j = -ln P(t_j)} of the OIS curve and then the projection curve.
 * Differentiating that identity (implicit function theorem) gives the pillar
 * response to a quote move without re-solving anything:</p>
 * <pre>
 *   J = dI/dY            (analytic, per instrument; square, block lower triangular)
 *   dY/dq = J^{-1}
 *   dPV/dq = (dPV/dY)^T J^{-1}       bucketed DV01_k = 1bp * (J^{-T} dPV/dY)_k
 * </pre>
 * <p>{@code J} is built in closed form from each instrument's implied-quote
 * formula and the curve's interpolation weights {@code dy(t)/dy_j}: exact
 * for {@link CurveInterpolation#LOG_LINEAR_DISCOUNT}; for
 * {@link CurveInterpolation#MONOTONE_CONVEX} the instrument formulas stay
 * analytic but the interpolation weights are central differences of the
 * interpolant (no re-bootstrap). {@code dPV/dY} is a central difference in
 * the pillar values, because the PV function is a black box.</p>
 *
 * <p>Worked example and check: on the test OIS + 3M curve set with
 * log-linear interpolation, the buckets match the bump-and-rebuild
 * {@link MultiCurveRisk#bucketedDv01} to about 1e-6 relative (the
 * difference is the second-order term of a 1bp central bump); the Jacobian
 * itself matches a finite difference of the implied quotes to 1e-6. With
 * monotone-convex interpolation it matches a 1e-7 quote-bump rebuild to
 * 1e-4 of the largest bucket, while the 1bp rebuild of
 * {@link MultiCurveRisk} can be off by about 10% in single buckets, because
 * a 1bp move crosses the interpolant's shape-zone kinks. That is the
 * practical case for this class.</p>
 */
public final class BootstrapJacobian {

    private static final double BP = 1e-4;
    private static final double H = 1e-6;

    private BootstrapJacobian() {
    }

    /**
     * {@code dI_k/dY_j}: rows are the OIS instruments then the projection
     * instruments (input order), columns the OIS curve pillars then the
     * projection curve pillars (pillar order). With an empty projection list
     * the projection curve is the OIS curve and the matrix is OIS-only.
     *
     * @param curves                curves built from these instruments ({@link MultiCurveRisk#buildCurves})
     * @param oisInstruments        OIS curve instruments
     * @param projectionInstruments projection curve instruments (may be empty)
     * @return the square Jacobian
     */
    public static double[][] quoteJacobian(CurvePair curves, List<CurveInstrument> oisInstruments,
                                           List<CurveInstrument> projectionInstruments) {
        Objects.requireNonNull(curves, "curves");
        DiscountCurve d = curves.discount();
        DiscountCurve p = curves.projection();
        int nO = d.pillarCount();
        boolean two = !projectionInstruments.isEmpty();
        int nP = two ? p.pillarCount() : 0;
        if (oisInstruments.size() != nO || projectionInstruments.size() != nP) {
            throw new IllegalArgumentException("instrument counts do not match the curve pillars");
        }
        int n = nO + nP;
        double[][] jac = new double[n][];
        for (int k = 0; k < nO; k++) {
            double[][] g = partials(oisInstruments.get(k), d, d);
            double[] row = new double[n];
            for (int j = 0; j < nO; j++) {
                row[j] = g[0][j] + g[1][j];
            }
            jac[k] = row;
        }
        for (int k = 0; k < nP; k++) {
            double[][] g = partials(projectionInstruments.get(k), d, p);
            double[] row = new double[n];
            System.arraycopy(g[0], 0, row, 0, nO);
            System.arraycopy(g[1], 0, row, nO, nP);
            jac[nO + k] = row;
        }
        return jac;
    }

    /**
     * Per-quote DV01 with the same contract and ordering as
     * {@link MultiCurveRisk#bucketedDv01}: the PV change for +1bp in each
     * quote, OIS quotes first, by the implicit-function Jacobian.
     *
     * @param valuationDate         valuation date
     * @param oisInstruments        OIS curve instruments
     * @param projectionInstruments projection curve instruments (may be empty)
     * @param interpolation         curve interpolation
     * @param pv                    PV as a function of (discount, projection)
     * @return bucketed DV01s
     */
    public static double[] bucketedDv01(LocalDate valuationDate, List<CurveInstrument> oisInstruments,
                                        List<CurveInstrument> projectionInstruments,
                                        CurveInterpolation interpolation,
                                        ToDoubleBiFunction<DiscountCurve, DiscountCurve> pv) {
        Objects.requireNonNull(pv, "pv");
        CurvePair curves = MultiCurveRisk.buildCurves(valuationDate, oisInstruments,
                projectionInstruments, interpolation);
        double[][] jac = quoteJacobian(curves, oisInstruments, projectionInstruments);
        double[] g = pvGradient(curves, !projectionInstruments.isEmpty(), pv);
        int n = g.length;
        double[][] jt = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                jt[i][j] = jac[j][i];
            }
        }
        double[] z = MultiCurveBootstrap.solveLinear(jt, g);
        for (int i = 0; i < n; i++) {
            z[i] *= BP;
        }
        return z;
    }

    /** dPV/dY by central differences in the pillar values (OIS pillars, then projection). */
    static double[] pvGradient(CurvePair curves, boolean two,
                               ToDoubleBiFunction<DiscountCurve, DiscountCurve> pv) {
        DiscountCurve d = curves.discount();
        DiscountCurve p = curves.projection();
        int nO = d.pillarCount();
        int nP = two ? p.pillarCount() : 0;
        double[] g = new double[nO + nP];
        for (int j = 0; j < nO; j++) {
            DiscountCurve up = bump(d, j, H);
            DiscountCurve dn = bump(d, j, -H);
            g[j] = (pv.applyAsDouble(up, two ? p : up) - pv.applyAsDouble(dn, two ? p : dn)) / (2 * H);
        }
        for (int j = 0; j < nP; j++) {
            g[nO + j] = (pv.applyAsDouble(d, bump(p, j, H)) - pv.applyAsDouble(d, bump(p, j, -H)))
                    / (2 * H);
        }
        return g;
    }

    private static DiscountCurve bump(DiscountCurve c, int j, double h) {
        double[] y = c.pillarMinusLogDf();
        y[j] += h;
        return DiscountCurve.ofMinusLogDf(c.valuationDate(), c.pillarTimes(), y, c.interpolation());
    }

    /** {dI/dy_discount, dI/dy_projection} of one instrument, analytic. */
    static double[][] partials(CurveInstrument c, DiscountCurve d, DiscountCurve p) {
        double[] gd = new double[d.pillarCount()];
        double[] gp = new double[p.pillarCount()];
        switch (c) {
            case Deposit x -> simpleForward(p, x.start(), x.end(), x.dayCount(), 0, 1, gp);
            case Fra x -> simpleForward(p, x.start(), x.end(), x.dayCount(), 0, 1, gp);
            case RateFuture x -> {
                double t1 = p.time(x.start());
                double t2 = p.time(x.end());
                simpleForward(p, x.start(), x.end(), x.dayCount(), x.convexityAdjustment(p) * (t2 - t1),
                        1, gp);
            }
            case OisSwap x -> {
                List<LocalDate> pay = x.paymentDates();
                double a = x.annuity(d);
                double k = x.parRate(d);
                double[] da = annuityGradient(d, x.start(), pay, x.dayCount());
                double ps = d.discountFactor(x.start());
                double pn = d.discountFactor(x.pillarDate());
                double[] ws = weights(d, x.start());
                double[] wn = weights(d, x.pillarDate());
                for (int j = 0; j < gd.length; j++) {
                    gd[j] = (-ps * ws[j] + pn * wn[j] - k * da[j]) / a;
                }
            }
            case InterestRateSwap x -> {
                double a = x.annuity(d);
                double k = x.parRate(d, p);
                double[] da = annuityGradient(d, x.start(), x.fixedPaymentDates(), x.fixedDayCount());
                LocalDate prev = x.start();
                for (LocalDate t : x.floatPaymentDates()) {
                    double tauF = Math.expm1(p.minusLogDf(p.time(t)) - p.minusLogDf(p.time(prev)));
                    double pd = d.discountFactor(t);
                    double growth = 1 + tauF;
                    double[] we = weights(p, t);
                    double[] wsP = weights(p, prev);
                    double[] wd = weights(d, t);
                    for (int j = 0; j < gp.length; j++) {
                        gp[j] += pd * growth * (we[j] - wsP[j]) / a;
                    }
                    for (int j = 0; j < gd.length; j++) {
                        gd[j] += -tauF * pd * wd[j] / a;
                    }
                    prev = t;
                }
                for (int j = 0; j < gd.length; j++) {
                    gd[j] -= k * da[j] / a;
                }
            }
        }
        return new double[][] {gd, gp};
    }

    /** Adds d/dy of expm1(y(e) - y(s) + extra)/tau, times scale, into g. */
    private static void simpleForward(DiscountCurve p, LocalDate s, LocalDate e, DayCount dc,
                                      double extra, double scale, double[] g) {
        double tau = dc.yearFraction(s, e);
        double growth = Math.exp(p.minusLogDf(p.time(e)) - p.minusLogDf(p.time(s)) + extra);
        double[] we = weights(p, e);
        double[] ws = weights(p, s);
        for (int j = 0; j < g.length; j++) {
            g[j] += scale * growth * (we[j] - ws[j]) / tau;
        }
    }

    /** d/dy of sum tau_i P(t_i). */
    private static double[] annuityGradient(DiscountCurve d, LocalDate start, List<LocalDate> pay,
                                            DayCount dc) {
        double[] g = new double[d.pillarCount()];
        LocalDate prev = start;
        for (LocalDate t : pay) {
            double f = -dc.yearFraction(prev, t) * d.discountFactor(t);
            double[] w = weights(d, t);
            for (int j = 0; j < g.length; j++) {
                g[j] += f * w[j];
            }
            prev = t;
        }
        return g;
    }

    private static double[] weights(DiscountCurve c, LocalDate date) {
        return c.minusLogDfWeights(c.time(date));
    }
}
