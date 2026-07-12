package com.quantfinlib.execution;

import com.quantfinlib.microstructure.AlmgrenChriss;

import java.util.ArrayList;
import java.util.List;

/**
 * Implementation-shortfall (arrival-price) schedule: turns the
 * {@link AlmgrenChriss} optimal trajectory into executable {@link Slice}s.
 * Where TWAP spreads evenly and VWAP follows the volume curve, IS
 * front-loads with urgency κ — trade more now to cut exposure to price
 * drift, but not so fast that temporary impact dominates; risk aversion λ
 * sets the balance (λ→0 degrades to TWAP, exactly as the math says).
 *
 * <p>Slice quantities are integer-allocated largest-remainder style so they
 * always sum exactly to the parent quantity.</p>
 */
public final class ImplementationShortfallScheduler {

    private ImplementationShortfallScheduler() {
    }

    /**
     * The optimal IS schedule for the given market parameters.
     *
     * @param params         Almgren-Chriss inputs ({@code totalShares} is the parent)
     * @param durationMillis wall-clock execution window the horizon maps onto
     */
    public static List<Slice> schedule(AlmgrenChriss.Params params, long durationMillis) {
        AlmgrenChriss.Trajectory t = AlmgrenChriss.optimalTrajectory(params);
        double[] weights = t.trades();
        for (double w : weights) {
            if (!Double.isFinite(w)) {
                // sinh overflows around kappa*T ~ 710; without this guard the
                // NaN weights would silently degrade the integer allocation
                // into an O(parentShares) loop yielding a garbage schedule.
                throw new IllegalArgumentException(
                        "risk aversion too high for these parameters (sinh overflow"
                                + " in the AC trajectory) — reduce riskAversion or"
                                + " shorten the horizon");
            }
        }
        if (durationMillis < 0 || durationMillis > Long.MAX_VALUE / weights.length) {
            throw new IllegalArgumentException(
                    "durationMillis out of range for " + weights.length + " slices");
        }
        long parent = Math.round(params.totalShares());
        long[] quantities = VwapScheduler.allocateProportionally(parent, weights);
        List<Slice> out = new ArrayList<>(weights.length);
        for (int i = 0; i < weights.length; i++) {
            out.add(new Slice(durationMillis * i / weights.length, quantities[i]));
        }
        return out;
    }

    /**
     * Convenience urgency calibration: the risk aversion whose first slice
     * is roughly {@code frontLoadFraction} of the parent (e.g. 0.3 = "30%
     * up front"), found by bisection on λ. Useful when traders think in
     * front-load, not in λ.
     */
    public static double riskAversionForFrontLoad(AlmgrenChriss.Params base,
                                                  double frontLoadFraction) {
        if (frontLoadFraction <= 1.0 / base.intervals() || frontLoadFraction >= 1) {
            throw new IllegalArgumentException(
                    "frontLoadFraction must exceed the TWAP slice and be below 1");
        }
        double lo = 0;
        double hi = 1;
        // Grow hi until it front-loads enough. A NaN fraction (sinh overflow
        // at huge lambda) counts as "more than enough": it stops the growth
        // and bisection then converges back below the overflow boundary.
        for (int i = 0; i < 60 && frontLoadOf(base, hi) < frontLoadFraction; i++) {
            hi *= 4;
        }
        for (int i = 0; i < 80; i++) {
            double mid = (lo + hi) / 2;
            if (frontLoadOf(base, mid) < frontLoadFraction) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        double lambda = (lo + hi) / 2;
        // Never return silently-wrong urgency: verify the calibration landed.
        double achieved = frontLoadOf(base, lambda);
        if (!(Math.abs(achieved - frontLoadFraction) < 0.01)) {
            throw new IllegalArgumentException(String.format(
                    "front-load %.2f unreachable for these parameters"
                            + " (best achievable near %.3f) — the AC trajectory"
                            + " overflows before front-loading that hard",
                    frontLoadFraction, achieved));
        }
        return lambda;
    }

    /** First-slice fraction, NaN mapped to +∞ so overflow reads as "too front-loaded". */
    private static double frontLoadOf(AlmgrenChriss.Params base, double lambda) {
        AlmgrenChriss.Trajectory t =
                AlmgrenChriss.optimalTrajectory(base.withRiskAversion(lambda));
        double f = t.trades()[0] / base.totalShares();
        return Double.isNaN(f) ? Double.POSITIVE_INFINITY : f;
    }
}
