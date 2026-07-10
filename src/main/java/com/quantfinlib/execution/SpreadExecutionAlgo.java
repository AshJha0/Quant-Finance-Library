package com.quantfinlib.execution;

/**
 * Two-legged spread execution with LEGGING-RISK control — pairs trades,
 * cash-vs-futures basis, stub-vs-hedge: the trade is the SPREAD, and
 * the risk is the moment you own one leg without the other. The
 * discipline every spread desk runs:
 *
 * <ul>
 *   <li>the LEAD leg (the illiquid one — single name, off-the-run,
 *       basis leg) is worked patiently, because it is the constraint;</li>
 *   <li>the HEDGE leg (the liquid one — future, ETF, benchmark) CHASES
 *       the lead leg's fills at the spread ratio, because liquidity is
 *       cheap there;</li>
 *   <li>the legging imbalance |executedLead·ratio − executedHedge| is
 *       capped: at the cap the algo stops adding lead risk entirely and
 *       the hedge child becomes the full imbalance — cross it, pay the
 *       spread, get flat. An imbalance cap that yields is not a cap.</li>
 * </ul>
 *
 * <p>Quantities are positive per leg (the buy/sell directions are the
 * caller's order tickets); the ratio is hedge units per lead unit.
 * Deterministic, single-threaded, research/warm lane — feed the
 * children to your routers ({@code AdaptiveSor}, {@code CrbRouter}).</p>
 */
public final class SpreadExecutionAlgo {

    /** This interval's child quantities, per leg. */
    public record Children(long leadQty, long hedgeQty, boolean atRiskCap) {
    }

    private final long leadParent;
    private final double ratio;
    private final long leggingLimit;       // in hedge-leg units
    private final long leadChildMax;
    private long leadExecuted;
    private long hedgeExecuted;

    /**
     * @param leadParentQty total lead-leg quantity to execute, &gt; 0
     * @param hedgePerLeadUnit spread ratio: hedge units per lead unit, &gt; 0
     * @param leggingLimitHedgeUnits max |lead·ratio − hedge| imbalance
     *                               tolerated, ≥ 1 hedge unit
     * @param leadChildMax largest lead child per decision, &gt; 0 (the
     *                     patience knob — small children, passive fills)
     */
    public SpreadExecutionAlgo(long leadParentQty, double hedgePerLeadUnit,
                               long leggingLimitHedgeUnits, long leadChildMax) {
        if (leadParentQty <= 0) {
            throw new IllegalArgumentException("leadParentQty must be > 0");
        }
        if (!(hedgePerLeadUnit > 0) || hedgePerLeadUnit == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("ratio must be positive and finite");
        }
        if (leggingLimitHedgeUnits < 1) {
            throw new IllegalArgumentException("leggingLimit must be >= 1 hedge unit");
        }
        if (leadChildMax <= 0) {
            throw new IllegalArgumentException("leadChildMax must be > 0");
        }
        this.leadParent = leadParentQty;
        this.ratio = hedgePerLeadUnit;
        this.leggingLimit = leggingLimitHedgeUnits;
        this.leadChildMax = leadChildMax;
    }

    /**
     * The next children. The hedge chases the CURRENT imbalance; the
     * lead child is sized so even a full fill cannot push the projected
     * imbalance past the cap (assuming the hedge child also fills —
     * the cap protects against the hedge NOT filling, which is why the
     * lead stops entirely at the cap).
     */
    public Children decide() {
        long imbalance = imbalanceHedgeUnits();
        long hedgeQty = Math.max(0, imbalance);
        long leadRemaining = leadParent - leadExecuted;
        long leadQty;
        boolean atCap = imbalance >= leggingLimit;
        if (atCap) {
            // Stop adding lead risk; the hedge child is the whole
            // imbalance — cross it and get flat.
            leadQty = 0;
        } else {
            // Even a full lead fill with NO hedge fill stays inside the cap.
            long headroomLeadUnits = (long) Math.floor((leggingLimit - imbalance) / ratio);
            leadQty = Math.min(Math.min(leadRemaining, leadChildMax), headroomLeadUnits);
        }
        return new Children(leadQty, hedgeQty, atCap);
    }

    /** Lead-leg fill. */
    public void onLeadFill(long qty) {
        if (qty < 0 || leadExecuted + qty > leadParent) {
            throw new IllegalArgumentException("lead fill " + qty + " overfills parent");
        }
        leadExecuted += qty;
    }

    /** Hedge-leg fill. */
    public void onHedgeFill(long qty) {
        if (qty < 0) {
            throw new IllegalArgumentException("hedge fill must be >= 0");
        }
        hedgeExecuted += qty;
    }

    /** Current legging imbalance in hedge units (positive = hedge behind). */
    public long imbalanceHedgeUnits() {
        return Math.round(leadExecuted * ratio) - hedgeExecuted;
    }

    public long leadExecuted() {
        return leadExecuted;
    }

    public long hedgeExecuted() {
        return hedgeExecuted;
    }

    /** Done when the lead is complete AND the hedge has caught up. */
    public boolean done() {
        return leadExecuted == leadParent && imbalanceHedgeUnits() <= 0;
    }
}
