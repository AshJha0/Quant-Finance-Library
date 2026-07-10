package com.quantfinlib.crb;

/**
 * The central risk book's hedging loop: per-factor exposure BANDS, a
 * cost-aware hedge when breached, and a cooldown so the book does not
 * chase its own hedges. The policy is deliberately two-speed:
 *
 * <ul>
 *   <li>inside the bands, warehouse — the whole point of a CRB is that
 *       inventory nets against future flow for free;</li>
 *   <li>on a breach, hedge the breached factors back to
 *       {@code resetFraction} of the limit through
 *       {@link HedgeOptimizer} — cost-aware first, but if the
 *       cost-aware hedge leaves any factor still OUTSIDE its limit the
 *       hedge reruns at zero cost weight: a hard limit outranks
 *       transaction-cost thrift, always.</li>
 * </ul>
 *
 * <p>Time is a caller-supplied interval counter (no wall clock —
 * deterministic, replayable). Research/warm lane, single-threaded.</p>
 */
public final class CrbAutoHedger {

    /** One instrument's hedge instruction. */
    public record HedgeOrder(int instrument, double notional) {
    }

    private final double[] limits;
    private final double resetFraction;
    private final long cooldownIntervals;
    private boolean hasHedged;
    private long lastHedgeInterval;
    private long hedgesEmitted;

    /**
     * @param limits            per-factor |exposure| limits (registry
     *                          order; &gt; 0 each)
     * @param resetFraction     post-hedge target as a fraction of the
     *                          limit, in (0, 1]
     * @param cooldownIntervals min intervals between hedges, ≥ 0
     */
    public CrbAutoHedger(double[] limits, double resetFraction, long cooldownIntervals) {
        for (double l : limits) {
            if (!(l > 0) || l == Double.POSITIVE_INFINITY) {
                throw new IllegalArgumentException("every limit must be positive and finite");
            }
        }
        if (!(resetFraction > 0 && resetFraction <= 1)) {
            throw new IllegalArgumentException("resetFraction must be in (0, 1]");
        }
        if (cooldownIntervals < 0) {
            throw new IllegalArgumentException("cooldownIntervals must be >= 0");
        }
        this.limits = limits.clone();
        this.resetFraction = resetFraction;
        this.cooldownIntervals = cooldownIntervals;
    }

    /** True when any factor sits outside its band. */
    public boolean breached(double[] exposures) {
        requireLength(exposures);
        for (int f = 0; f < limits.length; f++) {
            if (Math.abs(exposures[f]) > limits[f]) {
                return true;
            }
        }
        return false;
    }

    /**
     * The hedging decision for this interval. Empty when inside all
     * bands or still cooling down; otherwise the cheapest hedge of the
     * EXCESS — only what sits beyond {@code resetFraction·limit} on the
     * breached factors is hedged, because inventory inside the band is
     * the CRB's edge, not its problem. If the cost-aware hedge still
     * leaves a factor outside its hard limit, the excess is re-hedged
     * cost-blind (best effort: an instrument set that cannot span a
     * factor cannot fix it at any λ, and the orders go out anyway).
     *
     * @param exposures   current factor exposures (registry order)
     * @param covariance  factor covariance for the optimizer
     * @param loadings    instrument factor loadings [factor][instrument]
     * @param costPerUnit per-unit hedge costs
     * @param costWeight  λ for the cost-aware first attempt
     * @param nowInterval caller's interval counter (monotone)
     */
    public HedgeOrder[] check(double[] exposures, double[][] covariance,
                              double[][] loadings, double[] costPerUnit,
                              double costWeight, long nowInterval) {
        requireLength(exposures);
        if (!breached(exposures)) {
            return new HedgeOrder[0];
        }
        // hasHedged guards the subtraction: now − MIN_VALUE would overflow
        // negative and suppress the very FIRST hedge forever.
        if (hasHedged && nowInterval - lastHedgeInterval < cooldownIntervals) {
            return new HedgeOrder[0];
        }
        // Hedge target: the excess beyond the reset band on breached
        // factors, zero elsewhere — flattening the whole book would
        // throw away exactly the inventory the CRB exists to warehouse.
        double[] excess = new double[limits.length];
        for (int f = 0; f < limits.length; f++) {
            double band = limits[f] * resetFraction;
            if (Math.abs(exposures[f]) > limits[f]) {
                excess[f] = Math.signum(exposures[f]) * (Math.abs(exposures[f]) - band);
            }
        }
        double[] h = HedgeOptimizer.hedge(excess, covariance, loadings,
                costPerUnit, costWeight);
        if (stillBreached(HedgeOptimizer.residual(exposures, loadings, h))) {
            // The limit is hard; the cost preference is not.
            h = HedgeOptimizer.hedge(excess, covariance, loadings, costPerUnit, 0);
        }
        // Dust filter: coordinate descent converges instruments that
        // belong at zero only to ~tolerance of the largest notional —
        // no desk sends a sub-cent hedge order to the street.
        double maxH = 0;
        for (double v : h) {
            maxH = Math.max(maxH, Math.abs(v));
        }
        double dust = 1e-6 * maxH;
        int count = 0;
        for (double v : h) {
            if (Math.abs(v) > dust) {
                count++;
            }
        }
        HedgeOrder[] orders = new HedgeOrder[count];
        int k = 0;
        for (int i = 0; i < h.length; i++) {
            if (Math.abs(h[i]) > dust) {
                orders[k++] = new HedgeOrder(i, h[i]);
            }
        }
        if (orders.length > 0) {
            hasHedged = true;
            lastHedgeInterval = nowInterval;
            hedgesEmitted++;
        }
        return orders;
    }

    /** The band the book hedges back INTO for a factor. */
    public double targetBand(int factor) {
        return limits[factor] * resetFraction;
    }

    public long hedgesEmitted() {
        return hedgesEmitted;
    }

    private boolean stillBreached(double[] residual) {
        for (int f = 0; f < limits.length; f++) {
            if (Math.abs(residual[f]) > limits[f]) {
                return true;
            }
        }
        return false;
    }

    private void requireLength(double[] exposures) {
        if (exposures.length != limits.length) {
            throw new IllegalArgumentException("exposures length " + exposures.length
                    + " != limits length " + limits.length);
        }
        for (double e : exposures) {
            // Math.abs(NaN) > limit is FALSE: an unguarded NaN exposure
            // would read as "inside the band" and silently disable the
            // auto-hedger for that factor forever.
            if (!Double.isFinite(e)) {
                throw new IllegalArgumentException("exposures must be finite");
            }
        }
    }
}
