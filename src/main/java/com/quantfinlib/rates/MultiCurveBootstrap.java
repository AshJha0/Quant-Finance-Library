package com.quantfinlib.rates;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Multi-curve bootstrap: an OIS discount curve fitted to its own
 * instruments, then projection curves (3M, 6M, ...) fitted with the OIS
 * curve held fixed for discounting (Ametrano and Bianchetti 2013, SSRN
 * 2219548, sections 3-4).
 *
 * <p>One pillar per instrument, at its {@link CurveInstrument#pillarDate()};
 * pillar dates must be distinct and after the valuation date. The unknowns
 * are {@code y_i = -ln P(t_i)}. The solve has two stages:</p>
 * <ol>
 *   <li>Pillar by pillar: for instrument k, a one-dimensional Newton
 *       iteration on {@code y_k} with the earlier pillars fixed and the curve
 *       extrapolated past pillar k. Exact for
 *       {@link CurveInterpolation#LOG_LINEAR_DISCOUNT}, whose interpolation
 *       is local.</li>
 *   <li>A global Newton solve on all pillars together (finite-difference
 *       Jacobian, Gaussian elimination with partial pivoting), needed for
 *       {@link CurveInterpolation#MONOTONE_CONVEX}, whose node forwards
 *       couple neighbouring intervals.</li>
 * </ol>
 * <p>The result reprices every input instrument: {@code |implied - quote|}
 * is at most {@link #TOLERANCE}, else the bootstrap throws.</p>
 */
public final class MultiCurveBootstrap {

    /** Maximum absolute repricing error accepted, in rate units. */
    public static final double TOLERANCE = 1e-12;

    private static final int MAX_ITER = 50;
    private static final double FD_STEP = 1e-7;

    private MultiCurveBootstrap() {
    }

    /**
     * A self-discounting curve (OIS: deposits on the overnight rate and OIS
     * swaps). Every instrument is evaluated as
     * {@code impliedQuote(curve, curve)}.
     */
    public static DiscountCurve bootstrap(LocalDate valuationDate, List<CurveInstrument> instruments,
                                          CurveInterpolation interpolation) {
        return solve(valuationDate, instruments, interpolation, null);
    }

    /**
     * A projection curve, discounting on the fixed {@code discount} curve.
     * {@link OisSwap} is rejected: it does not depend on the projection curve.
     */
    public static DiscountCurve bootstrapProjection(LocalDate valuationDate,
                                                    List<CurveInstrument> instruments,
                                                    CurveInterpolation interpolation,
                                                    DiscountCurve discount) {
        Objects.requireNonNull(discount, "discount");
        if (!discount.valuationDate().equals(valuationDate)) {
            throw new IllegalArgumentException("discount curve has a different valuation date");
        }
        for (CurveInstrument c : instruments) {
            if (c instanceof OisSwap) {
                throw new IllegalArgumentException("an OisSwap does not depend on a projection curve");
            }
        }
        return solve(valuationDate, instruments, interpolation, discount);
    }

    private static DiscountCurve solve(LocalDate valuationDate, List<CurveInstrument> input,
                                       CurveInterpolation interpolation, DiscountCurve discount) {
        Objects.requireNonNull(valuationDate, "valuationDate");
        Objects.requireNonNull(interpolation, "interpolation");
        if (input.isEmpty()) {
            throw new IllegalArgumentException("need at least one instrument");
        }
        List<CurveInstrument> inst = new ArrayList<>(input);
        inst.sort(Comparator.comparing(CurveInstrument::pillarDate));
        int n = inst.size();
        double[] times = new double[n];
        for (int i = 0; i < n; i++) {
            times[i] = DayCount.ACT_365.yearFraction(valuationDate, inst.get(i).pillarDate());
            if (!(times[i] > (i == 0 ? 0 : times[i - 1]))) {
                throw new IllegalArgumentException("pillar dates must be distinct and after the valuation date: "
                        + inst.get(i).pillarDate());
            }
        }
        double[] y = new double[n];
        // Stage 1: pillar by pillar.
        for (int k = 0; k < n; k++) {
            double[] tk = java.util.Arrays.copyOf(times, k + 1);
            double[] yk = java.util.Arrays.copyOf(y, k + 1);
            yk[k] = k == 0 ? 0.02 * tk[0] : yk[k - 1] * tk[k] / tk[k - 1];
            CurveInstrument c = inst.get(k);
            for (int it = 0; it < MAX_ITER; it++) {
                double r = residual(c, valuationDate, tk, yk, interpolation, discount);
                if (Math.abs(r) <= TOLERANCE * 1e-2) {
                    break;
                }
                double yk0 = yk[k];
                yk[k] = yk0 + FD_STEP;
                double d = (residual(c, valuationDate, tk, yk, interpolation, discount) - r) / FD_STEP;
                if (!(Math.abs(d) > 0) || !Double.isFinite(d)) {
                    throw new IllegalStateException("instrument insensitive to its pillar: " + c);
                }
                yk[k] = yk0 - r / d;
            }
            y[k] = yk[k];
        }
        // Stage 2: global Newton.
        double[] r = residuals(inst, valuationDate, times, y, interpolation, discount);
        for (int it = 0; it < MAX_ITER && maxAbs(r) > TOLERANCE * 1e-2; it++) {
            double[][] jac = new double[n][n];
            for (int j = 0; j < n; j++) {
                double yj = y[j];
                y[j] = yj + FD_STEP;
                double[] rb = residuals(inst, valuationDate, times, y, interpolation, discount);
                y[j] = yj;
                for (int i = 0; i < n; i++) {
                    jac[i][j] = (rb[i] - r[i]) / FD_STEP;
                }
            }
            double[] step = solveLinear(jac, r);
            for (int i = 0; i < n; i++) {
                y[i] -= step[i];
            }
            double[] rNew = residuals(inst, valuationDate, times, y, interpolation, discount);
            boolean stalled = !(maxAbs(rNew) < maxAbs(r));
            r = rNew;
            if (stalled) {
                break;
            }
        }
        if (!(maxAbs(r) <= TOLERANCE)) {
            throw new IllegalStateException("bootstrap did not converge, max residual " + maxAbs(r));
        }
        return DiscountCurve.ofMinusLogDf(valuationDate, times, y, interpolation);
    }

    private static double residual(CurveInstrument c, LocalDate val, double[] t, double[] y,
                                   CurveInterpolation interp, DiscountCurve discount) {
        DiscountCurve curve = DiscountCurve.ofMinusLogDf(val, t, y, interp);
        return c.impliedQuote(discount == null ? curve : discount, curve) - c.quote();
    }

    private static double[] residuals(List<CurveInstrument> inst, LocalDate val, double[] t,
                                      double[] y, CurveInterpolation interp, DiscountCurve discount) {
        DiscountCurve curve = DiscountCurve.ofMinusLogDf(val, t, y, interp);
        DiscountCurve d = discount == null ? curve : discount;
        double[] r = new double[inst.size()];
        for (int i = 0; i < r.length; i++) {
            r[i] = inst.get(i).impliedQuote(d, curve) - inst.get(i).quote();
        }
        return r;
    }

    private static double maxAbs(double[] v) {
        double m = 0;
        for (double x : v) {
            if (!(Math.abs(x) <= m)) {
                m = Double.isNaN(x) ? Double.POSITIVE_INFINITY : Math.abs(x);
            }
        }
        return m;
    }

    /** Solves {@code a x = b} by Gaussian elimination with partial pivoting; a and b are overwritten. */
    static double[] solveLinear(double[][] a, double[] b) {
        int n = b.length;
        double[] x = b.clone();
        for (int col = 0; col < n; col++) {
            int piv = col;
            for (int row = col + 1; row < n; row++) {
                if (Math.abs(a[row][col]) > Math.abs(a[piv][col])) {
                    piv = row;
                }
            }
            if (!(Math.abs(a[piv][col]) > 0)) {
                throw new IllegalStateException("singular bootstrap Jacobian at column " + col);
            }
            double[] tmp = a[col];
            a[col] = a[piv];
            a[piv] = tmp;
            double tb = x[col];
            x[col] = x[piv];
            x[piv] = tb;
            for (int row = col + 1; row < n; row++) {
                double f = a[row][col] / a[col][col];
                for (int k = col; k < n; k++) {
                    a[row][k] -= f * a[col][k];
                }
                x[row] -= f * x[col];
            }
        }
        for (int row = n - 1; row >= 0; row--) {
            double s = x[row];
            for (int k = row + 1; k < n; k++) {
                s -= a[row][k] * x[k];
            }
            x[row] = s / a[row][row];
        }
        return x;
    }
}
