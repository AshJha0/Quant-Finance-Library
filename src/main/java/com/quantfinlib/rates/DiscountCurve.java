package com.quantfinlib.rates;

import com.quantfinlib.util.Immutable;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A dated discount curve {@code P(t)} built from pillar discount factors:
 * the object both an OIS discount curve and a projection curve (for example
 * a 3M forwarding curve, a pseudo-discount curve whose ratios give the index
 * forwards) are made of. Built by {@link MultiCurveBootstrap} or directly
 * from pillars with {@link #of}.
 *
 * <p>Time axis: {@code t} is the ACT/365 (fixed) year fraction from the
 * valuation date, so {@code P(0) = 1}. Interpolation acts on
 * {@code y(t) = -ln P(t)}, see {@link CurveInterpolation}.</p>
 *
 * <p>Conventions of the derived quantities:</p>
 * <pre>
 *   instantaneous forward  f(t) = dy/dt                          (continuous comp.)
 *   zero rate              z(t) = y(t) / t                        (continuous comp.)
 *   simple forward         F(s,e) = (P(s)/P(e) - 1) / tau(s,e)    tau from the day count
 * </pre>
 */
@Immutable
public final class DiscountCurve {

    private final LocalDate valuationDate;
    private final CurveInterpolation interpolation;
    /** Times with t[0] = 0 prepended. */
    private final double[] t;
    /** y = -ln P at t, y[0] = 0. */
    private final double[] y;
    /** Discrete forward on (t[i-1], t[i]], index 1..n. */
    private final double[] fd;
    /** Node instantaneous forwards (monotone-convex only), index 0..n. */
    private final double[] fn;

    private DiscountCurve(LocalDate valuationDate, CurveInterpolation interpolation,
                          double[] times, double[] minusLogDf) {
        this.valuationDate = valuationDate;
        this.interpolation = interpolation;
        int n = times.length;
        t = new double[n + 1];
        y = new double[n + 1];
        System.arraycopy(times, 0, t, 1, n);
        System.arraycopy(minusLogDf, 0, y, 1, n);
        fd = new double[n + 1];
        for (int i = 1; i <= n; i++) {
            fd[i] = (y[i] - y[i - 1]) / (t[i] - t[i - 1]);
        }
        fn = new double[n + 1];
        if (interpolation == CurveInterpolation.MONOTONE_CONVEX) {
            nodeForwards();
        }
    }

    /**
     * A curve from pillar times (ACT/365F years after {@code valuationDate},
     * strictly increasing, positive) and their discount factors (positive).
     * Arrays are copied.
     */
    public static DiscountCurve of(LocalDate valuationDate, double[] times, double[] discountFactors,
                                   CurveInterpolation interpolation) {
        Objects.requireNonNull(valuationDate, "valuationDate");
        Objects.requireNonNull(interpolation, "interpolation");
        if (times.length == 0 || times.length != discountFactors.length) {
            throw new IllegalArgumentException("need equal-length, non-empty times and discount factors");
        }
        double[] ml = new double[times.length];
        double prev = 0;
        for (int i = 0; i < times.length; i++) {
            if (!(times[i] > prev) || !Double.isFinite(times[i])) {
                throw new IllegalArgumentException("times must be finite, positive and strictly increasing");
            }
            if (!(discountFactors[i] > 0) || !Double.isFinite(discountFactors[i])) {
                throw new IllegalArgumentException("discount factors must be positive and finite, got "
                        + discountFactors[i]);
            }
            prev = times[i];
            ml[i] = -Math.log(discountFactors[i]);
        }
        return new DiscountCurve(valuationDate, interpolation, times.clone(), ml);
    }

    /** Factory on {@code -ln P} values, arrays not copied (bootstrap use). */
    static DiscountCurve ofMinusLogDf(LocalDate valuationDate, double[] times, double[] minusLogDf,
                                      CurveInterpolation interpolation) {
        return new DiscountCurve(valuationDate, interpolation, times, minusLogDf);
    }

    /** Curve time of a date: ACT/365F years from the valuation date. */
    public double time(LocalDate date) {
        return DayCount.ACT_365.yearFraction(valuationDate, date);
    }

    public LocalDate valuationDate() {
        return valuationDate;
    }

    public CurveInterpolation interpolation() {
        return interpolation;
    }

    public int pillarCount() {
        return t.length - 1;
    }

    /** Time of pillar {@code i} (0-based). */
    public double pillarTime(int i) {
        return t[checkPillar(i) + 1];
    }

    /** Discount factor of pillar {@code i} (0-based). */
    public double pillarDiscountFactor(int i) {
        return Math.exp(-y[checkPillar(i) + 1]);
    }

    /** {@code P(t)} for {@code t >= 0}. */
    public double discountFactor(double time) {
        return Math.exp(-minusLogDf(time));
    }

    /** {@code P} at a date on or after the valuation date. */
    public double discountFactor(LocalDate date) {
        return discountFactor(time(date));
    }

    /** Continuously compounded zero rate {@code y(t)/t}; at {@code t = 0} the short forward. */
    public double zeroRate(double time) {
        return time == 0 ? instantaneousForward(0) : minusLogDf(time) / time;
    }

    /** Simple forward {@code (P(s)/P(e) - 1)/tau} with {@code tau} from {@code dayCount}. */
    public double forwardRate(LocalDate start, LocalDate end, DayCount dayCount) {
        double tau = dayCount.yearFraction(start, end);
        if (!(tau > 0)) {
            throw new IllegalArgumentException("end must be after start: " + start + " " + end);
        }
        return Math.expm1(minusLogDf(time(end)) - minusLogDf(time(start))) / tau;
    }

    /**
     * Instantaneous forward {@code f(t)}. Log-linear: the interval's constant
     * forward (right-continuous at pillars). Monotone-convex: continuous.
     */
    public double instantaneousForward(double time) {
        requireTime(time);
        int n = t.length - 1;
        boolean mc = interpolation == CurveInterpolation.MONOTONE_CONVEX;
        if (time >= t[n]) {
            return mc ? fn[n] : fd[n];
        }
        int i = interval(time);
        if (!mc) {
            return time == t[i] ? fd[i + 1] : fd[i];
        }
        double x = (time - t[i - 1]) / (t[i] - t[i - 1]);
        return fd[i] + shape(fn[i - 1] - fd[i], fn[i] - fd[i], x, false);
    }

    /**
     * A copy with every pillar's continuously compounded zero rate shifted by
     * {@code shift} (a parallel shift of the pillar zero curve).
     */
    public DiscountCurve parallelShift(double shift) {
        if (!Double.isFinite(shift)) {
            throw new IllegalArgumentException("shift must be finite");
        }
        int n = t.length - 1;
        double[] times = new double[n];
        double[] ml = new double[n];
        for (int i = 0; i < n; i++) {
            times[i] = t[i + 1];
            ml[i] = y[i + 1] + shift * t[i + 1];
        }
        return new DiscountCurve(valuationDate, interpolation, times, ml);
    }

    /** {@code y(t) = -ln P(t)}. */
    double minusLogDf(double time) {
        requireTime(time);
        if (time == 0) {
            return 0;
        }
        int n = t.length - 1;
        boolean mc = interpolation == CurveInterpolation.MONOTONE_CONVEX;
        if (time >= t[n]) {
            return y[n] + (mc ? fn[n] : fd[n]) * (time - t[n]);
        }
        int i = interval(time);
        double dt = time - t[i - 1];
        if (!mc) {
            return y[i - 1] + fd[i] * dt;
        }
        double h = t[i] - t[i - 1];
        return y[i - 1] + fd[i] * dt + h * shape(fn[i - 1] - fd[i], fn[i] - fd[i], dt / h, true);
    }

    /** Smallest i >= 1 with t[i] >= time, for 0 < time < t[n]. */
    private int interval(double time) {
        int lo = 1;
        int hi = t.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (t[mid] >= time) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return lo;
    }

    /**
     * Hagan and West (2006) node forwards: interior nodes take the
     * time-weighted average of the two adjacent discrete forwards, the end
     * nodes are set so the forward is linear through the end intervals'
     * averages ({@code f_0 = f^d_1 - (f_1 - f^d_1)/2}, and the mirror at
     * {@code t_n}).
     */
    private void nodeForwards() {
        int n = t.length - 1;
        if (n == 1) {
            fn[0] = fd[1];
            fn[1] = fd[1];
            return;
        }
        for (int i = 1; i < n; i++) {
            double w = t[i + 1] - t[i - 1];
            fn[i] = (t[i] - t[i - 1]) / w * fd[i + 1] + (t[i + 1] - t[i]) / w * fd[i];
        }
        fn[0] = fd[1] - 0.5 * (fn[1] - fd[1]);
        fn[n] = fd[n] - 0.5 * (fn[n - 1] - fd[n]);
    }

    /**
     * The monotone-convex shape on one interval (Hagan and West 2006,
     * section 4, zones (i)-(iv)): with {@code g0 = f(t_{i-1}) - f^d_i} and
     * {@code g1 = f(t_i) - f^d_i}, returns {@code g(x)} (the forward minus the
     * interval average, x in [0, 1]) or, if {@code integral}, {@code G(x)},
     * the integral of g from 0 to x. Every zone has {@code G(1) = 0}, so the
     * pillar discount factors are reproduced.
     */
    static double shape(double g0, double g1, double x, boolean integral) {
        if (x <= 0) {
            return integral ? 0 : g0;
        }
        if (g0 == 0 && g1 == 0) {
            return 0;
        }
        if ((g0 < 0 && -0.5 * g0 <= g1 && g1 <= -2 * g0)
                || (g0 > 0 && -0.5 * g0 >= g1 && g1 >= -2 * g0)) {
            // zone (i): one quadratic
            return integral
                    ? g0 * (x - 2 * x * x + x * x * x) + g1 * (-x * x + x * x * x)
                    : g0 * (1 - 4 * x + 3 * x * x) + g1 * (-2 * x + 3 * x * x);
        }
        if ((g0 < 0 && g1 > -2 * g0) || (g0 > 0 && g1 < -2 * g0)) {
            // zone (ii): flat at g0, then quadratic from eta
            double eta = (g1 + 2 * g0) / (g1 - g0);
            if (x <= eta) {
                return integral ? g0 * x : g0;
            }
            double u = (x - eta) / (1 - eta);
            return integral
                    ? g0 * x + (g1 - g0) * u * u * (x - eta) / 3
                    : g0 + (g1 - g0) * u * u;
        }
        if ((g0 > 0 && g1 < 0 && g1 > -0.5 * g0) || (g0 < 0 && g1 > 0 && g1 < -0.5 * g0)) {
            // zone (iii): quadratic until eta, then flat at g1
            double eta = 3 * g1 / (g1 - g0);
            if (x < eta) {
                double u = (eta - x) / eta;
                return integral
                        ? g1 * x + (g0 - g1) / 3 * (eta - u * u * (eta - x))
                        : g1 + (g0 - g1) * u * u;
            }
            return integral ? g1 * x + (g0 - g1) * eta / 3 : g1;
        }
        // zone (iv): g0 and g1 of the same sign, two quadratics meeting at eta
        double eta = g1 / (g1 + g0);
        double a = -g0 * g1 / (g0 + g1);
        if (x <= eta) {
            double u = (eta - x) / eta;
            return integral
                    ? a * x + (g0 - a) / 3 * (eta - u * u * (eta - x))
                    : a + (g0 - a) * u * u;
        }
        double u = (x - eta) / (1 - eta);
        return integral
                ? a * x + (g0 - a) * eta / 3 + (g1 - a) * u * u * (x - eta) / 3
                : a + (g1 - a) * u * u;
    }

    private static void requireTime(double time) {
        if (!(time >= 0) || !Double.isFinite(time)) {
            throw new IllegalArgumentException("time must be finite and >= 0, got " + time);
        }
    }

    private int checkPillar(int i) {
        if (i < 0 || i >= t.length - 1) {
            throw new IndexOutOfBoundsException("pillar " + i);
        }
        return i;
    }
}
