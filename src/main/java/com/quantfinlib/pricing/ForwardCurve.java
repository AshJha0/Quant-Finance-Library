package com.quantfinlib.pricing;

import java.util.Arrays;

/**
 * Implied FX forward curve from market outright forwards, with
 * interpolation, implied rate differentials, and covered-interest-parity
 * checks against deposit rates.
 *
 * <p>Thread safety: {@link #addPoint} is synchronized and publishes a new
 * immutable pillar array through a volatile field (copy on write), so
 * readers on other threads never see a half-updated curve and never block.</p>
 */
public final class ForwardCurve {

    private static final class Pillars {
        private final double[] tenors;
        private final double[] outrights;

        /**
         * Creates the value; array and collection arguments are copied.
         */
        Pillars(double[] tenors, double[] outrights) {
            this.tenors = tenors;
            this.outrights = outrights;
        }

        /** {@code tenors}. */
        double[] tenors() {
            return tenors;
        }

        /** {@code outrights}. */
        double[] outrights() {
            return outrights;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Pillars o)) {
                return false;
            }
            return java.util.Arrays.equals(tenors, o.tenors)
                    && java.util.Arrays.equals(outrights, o.outrights);
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Arrays.hashCode(tenors);
            h = 31 * h + java.util.Arrays.hashCode(outrights);
            return h;
        }

        @Override
        public String toString() {
            return "Pillars[" + "tenors=" + java.util.Arrays.toString(tenors) + ", " + "outrights=" + java.util.Arrays.toString(outrights) + "]";
        }
    }

    private final double spot;
    private volatile Pillars pillars;

    public ForwardCurve(double spot) {
        if (!(spot > 0) || !Double.isFinite(spot)) {
            throw new IllegalArgumentException("spot must be positive and finite");
        }
        this.spot = spot;
        this.pillars = new Pillars(new double[]{0.0}, new double[]{spot});
    }

    /** Adds (or replaces) an outright forward at {@code tenorYears > 0}. */
    public synchronized ForwardCurve addPoint(double tenorYears, double outrightForward) {
        if (!(tenorYears > 0) || !Double.isFinite(tenorYears)) {
            throw new IllegalArgumentException("tenor must be positive and finite");
        }
        if (!(outrightForward > 0) || !Double.isFinite(outrightForward)) {
            throw new IllegalArgumentException("outright forward must be positive and finite");
        }
        Pillars p = pillars;
        int idx = Arrays.binarySearch(p.tenors, tenorYears);
        double[] ts;
        double[] fs;
        if (idx >= 0) {
            ts = p.tenors;
            fs = p.outrights.clone();
            fs[idx] = outrightForward;
        } else {
            int at = -idx - 1;
            int n = p.tenors.length;
            ts = new double[n + 1];
            fs = new double[n + 1];
            System.arraycopy(p.tenors, 0, ts, 0, at);
            System.arraycopy(p.outrights, 0, fs, 0, at);
            ts[at] = tenorYears;
            fs[at] = outrightForward;
            System.arraycopy(p.tenors, at, ts, at + 1, n - at);
            System.arraycopy(p.outrights, at, fs, at + 1, n - at);
        }
        pillars = new Pillars(ts, fs);
        return this;
    }

    public double spot() {
        return spot;
    }

    /**
     * Interpolated outright forward at the tenor: linear in forward points
     * between pillars, extrapolated with the slope of the last two pillars
     * beyond the last one (unbounded, so keep queries near the quoted range).
     *
     * @throws IllegalStateException if no forward point has been added
     */
    public double forward(double tenorYears) {
        if (Double.isNaN(tenorYears)) {
            throw new IllegalArgumentException("tenor is NaN");
        }
        if (tenorYears <= 0) {
            return spot;
        }
        Pillars p = pillars;
        double[] ts = p.tenors;
        double[] fs = p.outrights;
        int n = ts.length;
        if (n < 2) {
            throw new IllegalStateException("no forward points on the curve");
        }
        int idx = Arrays.binarySearch(ts, tenorYears);
        if (idx >= 0) {
            return fs[idx];
        }
        int hi = -idx - 1;
        if (hi >= n) {
            double slope = (fs[n - 1] - fs[n - 2]) / (ts[n - 1] - ts[n - 2]);
            return fs[n - 1] + slope * (tenorYears - ts[n - 1]);
        }
        int lo = hi - 1;
        double w = (tenorYears - ts[lo]) / (ts[hi] - ts[lo]);
        return fs[lo] + w * (fs[hi] - fs[lo]);
    }

    /** Forward points at the tenor (outright minus spot). */
    public double forwardPoints(double tenorYears) {
        return forward(tenorYears) - spot;
    }

    /**
     * Implied continuously compounded rate differential (domestic minus
     * foreign) from covered interest parity, {@code F = S·e^{(rd−rf)t}}, for
     * {@code tenorYears > 0}.
     *
     * <p>This method is continuous while {@link #theoreticalForward} uses
     * simple deposit rates, each matching how its input is quoted. Feeding
     * this output back through {@code theoreticalForward} shows a spurious
     * basis (about 12bp at 1y and 5%) that is compounding convention, not
     * arbitrage; convert first.</p>
     */
    public double impliedRateDifferential(double tenorYears) {
        if (!(tenorYears > 0)) {
            throw new IllegalArgumentException("tenor must be > 0, got " + tenorYears);
        }
        return Math.log(forward(tenorYears) / spot) / tenorYears;
    }

    /** CIP-theoretical forward from simple deposit rates. */
    public static double theoreticalForward(double spot, double domesticRate, double foreignRate,
                                            double tenorYears) {
        return spot * (1 + domesticRate * tenorYears) / (1 + foreignRate * tenorYears);
    }

    /**
     * Covered-interest-parity check: market forward versus the
     * deposit-implied forward, in basis points (positive = market forward rich).
     */
    public double mispricingBps(double tenorYears, double domesticRate, double foreignRate) {
        double theoretical = theoreticalForward(spot, domesticRate, foreignRate, tenorYears);
        return (forward(tenorYears) - theoretical) / theoretical * 1e4;
    }
}
