package com.quantfinlib.credit;

import java.util.Arrays;

/**
 * A curve of the kind the ISDA CDS Standard Model works on: continuously
 * compounded zero rates r_i at node times t_i (years, ACT/365F from the
 * curve's base date), interpolated linearly in {@code r(t) * t}. That
 * makes the instantaneous forward piecewise constant between nodes,
 * which is what lets the model integrate its legs in closed form
 * segment by segment (ISDA CDS Standard Model, "ISDA CDS Standard Model
 * proposed numerical fix", Markit 2009, and the JPMorgan CDS library
 * {@code JpmcdsZeroPrice}). Before the first node the zero rate is held
 * at r_1 and after the last at r_n, so the forward there is r_1 and r_n.
 *
 * <p>The same object serves as a discount curve ({@link #value(double)}
 * is the discount factor P(t)) and as a survival curve (the "rate" is
 * the average hazard, {@code value(t)} is Q(t)). {@link #ofHazardRates}
 * builds the second from piecewise-constant hazards on (t_{i-1}, t_i].
 * Research lane, deterministic.</p>
 */
public final class IsdaCurve {

    private final double[] times;   // ascending, > 0
    private final double[] rt;      // r_i * t_i

    private IsdaCurve(double[] times, double[] rt) {
        this.times = times;
        this.rt = rt;
    }

    /**
     * Curve from zero rates.
     *
     * @param times     strictly ascending node times in years, all &gt; 0 and finite
     * @param zeroRates continuously compounded zero rates at the nodes, finite
     * @return the curve
     */
    public static IsdaCurve ofZeroRates(double[] times, double[] zeroRates) {
        validateTimes(times, zeroRates);
        double[] t = times.clone();
        double[] rt = new double[t.length];
        for (int i = 0; i < t.length; i++) {
            if (!Double.isFinite(zeroRates[i])) {
                throw new IllegalArgumentException("zero rate must be finite: " + zeroRates[i]);
            }
            rt[i] = zeroRates[i] * t[i];
        }
        return new IsdaCurve(t, rt);
    }

    /**
     * Survival curve from piecewise-constant hazards: {@code hazards[i]}
     * applies on (t_{i-1}, t_i] with t_{-1} = 0, and the last hazard
     * continues beyond the last node.
     *
     * @param times   strictly ascending node times in years, all &gt; 0 and finite
     * @param hazards hazards per year, &ge; 0 and finite
     * @return the curve, whose {@link #value(double)} is Q(t)
     */
    public static IsdaCurve ofHazardRates(double[] times, double[] hazards) {
        validateTimes(times, hazards);
        double[] t = times.clone();
        double[] rt = new double[t.length];
        double acc = 0;
        double prev = 0;
        for (int i = 0; i < t.length; i++) {
            if (!(hazards[i] >= 0) || hazards[i] == Double.POSITIVE_INFINITY) {
                throw new IllegalArgumentException("hazard must be >= 0 and finite: " + hazards[i]);
            }
            acc += hazards[i] * (t[i] - prev);
            rt[i] = acc;
            prev = t[i];
        }
        // Keep the last hazard in force beyond the last node with a far
        // node 1000 years out (zero-rate extrapolation would otherwise
        // switch to the average hazard).
        double[] t2 = Arrays.copyOf(t, t.length + 1);
        double[] rt2 = Arrays.copyOf(rt, rt.length + 1);
        t2[t.length] = t[t.length - 1] + 1000;
        rt2[t.length] = acc + hazards[t.length - 1] * 1000;
        return new IsdaCurve(t2, rt2);
    }

    /** A flat curve: constant zero rate (or constant hazard). */
    public static IsdaCurve flat(double rate) {
        return ofZeroRates(new double[]{1}, new double[]{rate});
    }

    private static void validateTimes(double[] times, double[] values) {
        if (times.length == 0 || times.length != values.length) {
            throw new IllegalArgumentException("need aligned, non-empty times/values, got "
                    + times.length + "/" + values.length);
        }
        double prev = 0;
        for (int i = 0; i < times.length; i++) {
            if (!(times[i] > prev) || times[i] == Double.POSITIVE_INFINITY) {
                throw new IllegalArgumentException(
                        "times must be strictly ascending, positive and finite: t[" + i + "]="
                                + times[i]);
            }
            prev = times[i];
        }
    }

    /**
     * {@code r(t) * t}, the integrated forward from 0 to t.
     *
     * @param t time in years, &ge; 0 and finite
     * @return the integral
     */
    public double rateTimesTime(double t) {
        if (!(t >= 0) || t == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("t must be >= 0 and finite, got " + t);
        }
        int n = times.length;
        if (t <= times[0]) {
            return rt[0] / times[0] * t;
        }
        if (t >= times[n - 1]) {
            return rt[n - 1] / times[n - 1] * t;
        }
        int i = Arrays.binarySearch(times, t);
        if (i >= 0) {
            return rt[i];
        }
        int hi = -i - 1;
        int lo = hi - 1;
        double w = (t - times[lo]) / (times[hi] - times[lo]);
        return rt[lo] + w * (rt[hi] - rt[lo]);
    }

    /** Continuously compounded zero rate at t &gt; 0. */
    public double zeroRate(double t) {
        if (!(t > 0)) {
            throw new IllegalArgumentException("t must be > 0, got " + t);
        }
        return rateTimesTime(t) / t;
    }

    /** {@code exp(-r(t) t)}: the discount factor, or the survival probability. */
    public double value(double t) {
        return Math.exp(-rateTimesTime(t));
    }

    /** Number of nodes. */
    public int timesLength() {
        return times.length;
    }

    /** Node time i. */
    public double time(int i) {
        return times[i];
    }

    /** Copy of the node times. */
    public double[] timesCopy() {
        return times.clone();
    }
}
