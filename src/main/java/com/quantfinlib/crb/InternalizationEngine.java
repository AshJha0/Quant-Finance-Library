package com.quantfinlib.crb;

/**
 * The internalize-or-route decision — the economics that justify a
 * central risk book's existence. Every internalized unit of flow saves
 * the street's spread and market impact twice over (the client's
 * execution and the eventual hedge), so:
 *
 * <ul>
 *   <li><b>Risk-reducing flow</b> (opposite sign to the book's net) is
 *       internalized up to the offsetting inventory, and the client is
 *       given back a share of the saved spread as price improvement —
 *       the book was going to pay to shed that risk anyway;</li>
 *   <li><b>Risk-adding flow</b> is warehoused (internalized without
 *       improvement) only while the post-trade inventory stays inside
 *       the warehouse limit — beyond that it routes out, because a
 *       warehouse limit that yields to one more trade is not a
 *       limit.</li>
 * </ul>
 *
 * <p>Flows and exposures are in the same factor units (currency
 * notional for {@code EQ:}/{@code CCY:} factors). Counters accumulate
 * for the {@link #internalizationRate()} the desk reports. Sign
 * convention matches {@link CentralRiskBook}: the flow is what the book
 * absorbs if it internalizes. Deterministic, single-threaded.</p>
 */
public final class InternalizationEngine {

    /** Where one flow went, and what the client got for it. */
    public record Decision(double internalized, double routed, double improvementBps) {
    }

    private final double warehouseLimit;
    private final double improvementShare;
    private double internalizedNotional;
    private double routedNotional;

    /**
     * @param warehouseLimit   max |inventory| the book will hold on a
     *                         factor after absorbing risk-adding flow, &gt; 0
     * @param improvementShare fraction of the half spread returned to a
     *                         risk-reducing client, in [0, 1]
     */
    private double lastRouted;
    private double lastImprovementBps;

    public InternalizationEngine(double warehouseLimit, double improvementShare) {
        if (!(warehouseLimit > 0) || warehouseLimit == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("warehouseLimit must be positive and finite");
        }
        if (!(improvementShare >= 0 && improvementShare <= 1)) {
            throw new IllegalArgumentException("improvementShare must be in [0, 1]");
        }
        this.warehouseLimit = warehouseLimit;
        this.improvementShare = improvementShare;
    }

    /**
     * Decides one flow against the book's current net on that factor.
     *
     * @param bookNet       the book's net exposure on the factor (signed)
     * @param flow          exposure change the book absorbs if it
     *                      internalizes (signed, non-zero)
     * @param halfSpreadBps the street's half spread for this risk —
     *                      what internalizing saves, &gt; 0
     */
    public Decision decide(double bookNet, double flow, double halfSpreadBps) {
        double internalized = internalize(bookNet, flow, halfSpreadBps);
        return new Decision(internalized, lastRouted, lastImprovementBps);
    }

    /**
     * The same decision as {@link #decide} without the result object: returns
     * the internalized notional and leaves the routed notional and the price
     * improvement in {@link #lastRouted()} and {@link #lastImprovementBps()}.
     * Allocates nothing, for a per-flow loop.
     *
     * @param bookNet       the book's current net on the flow's factor (signed)
     * @param flow          incoming client flow (signed, non-zero)
     * @param halfSpreadBps the street's half spread for this risk, &gt; 0
     * @return signed notional internalized
     */
    public double internalize(double bookNet, double flow, double halfSpreadBps) {
        if (!Double.isFinite(bookNet) || !Double.isFinite(flow) || flow == 0) {
            throw new IllegalArgumentException("bookNet/flow must be finite, flow non-zero");
        }
        if (!(halfSpreadBps > 0) || halfSpreadBps == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("halfSpreadBps must be positive and finite");
        }
        double internalized;
        double improvement = 0;
        if (bookNet != 0 && Math.signum(flow) != Math.signum(bookNet)) {
            // Risk-reducing: cross against inventory, share the saved spread.
            double reducing = Math.min(Math.abs(flow), Math.abs(bookNet));
            improvement = improvementShare * halfSpreadBps;
            // Whatever exceeds the offset flips the book's sign — that
            // excess is risk-adding and faces the warehouse test below.
            double excess = Math.abs(flow) - reducing;
            double warehoused = Math.min(excess, warehouseLimit);
            internalized = Math.signum(flow) * (reducing + warehoused);
            if (warehoused > 0 && excess > 0) {
                // Blended improvement: only the reducing portion earned it.
                improvement = improvement * reducing / (reducing + warehoused);
            }
        } else {
            // Risk-adding: warehouse only inside the limit.
            double headroom = Math.max(0, warehouseLimit - Math.abs(bookNet));
            internalized = Math.signum(flow) * Math.min(Math.abs(flow), headroom);
        }
        double routed = flow - internalized;
        internalizedNotional += Math.abs(internalized);
        routedNotional += Math.abs(routed);
        lastRouted = routed;
        lastImprovementBps = improvement;
        return internalized;
    }

    /**
     * Routed notional of the last {@link #internalize} or {@link #decide} call.
     *
     * @return signed notional sent to the street
     */
    public double lastRouted() {
        return lastRouted;
    }

    /**
     * Price improvement of the last {@link #internalize} or {@link #decide} call.
     *
     * @return improvement in bps
     */
    public double lastImprovementBps() {
        return lastImprovementBps;
    }

    /** Fraction of decided notional the book kept (0 before any flow). */
    public double internalizationRate() {
        double total = internalizedNotional + routedNotional;
        return total <= 0 ? 0 : internalizedNotional / total;
    }

    public double internalizedNotional() {
        return internalizedNotional;
    }

    public double routedNotional() {
        return routedNotional;
    }

    // ------------------------------------------------------------------
    // Overnight persistence (persist.Checkpoint section body)
    // ------------------------------------------------------------------

    /** Serializes the counters plus the configuration they were earned under. */
    public void writeState(java.io.DataOutput out) throws java.io.IOException {
        out.writeByte(1);
        out.writeDouble(warehouseLimit);
        out.writeDouble(improvementShare);
        out.writeDouble(internalizedNotional);
        out.writeDouble(routedNotional);
    }

    /**
     * Restores counters written by {@link #writeState}. The configuration
     * must match: an internalization rate earned under one warehouse
     * limit says nothing about a different one, so a mismatch throws
     * rather than silently blending regimes.
     */
    public void readState(java.io.DataInput in) throws java.io.IOException {
        com.quantfinlib.persist.Checkpoint.requireVersion(in, 1, "InternalizationEngine");
        double limit = in.readDouble();
        double share = in.readDouble();
        if (limit != warehouseLimit || share != improvementShare) {
            throw new IllegalStateException("configuration mismatch: saved ("
                    + limit + ", " + share + ") vs current ("
                    + warehouseLimit + ", " + improvementShare + ")");
        }
        internalizedNotional = in.readDouble();
        routedNotional = in.readDouble();
    }
}
