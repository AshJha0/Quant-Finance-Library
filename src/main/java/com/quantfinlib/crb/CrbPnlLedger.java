package com.quantfinlib.crb;

/**
 * The central risk book's ECONOMICS ledger — the number the desk head
 * actually asks for at the close: did the spread we captured by
 * internalizing pay for the hedging we did? A CRB that nets
 * beautifully but hedges expensively is a cost center with good
 * graphics.
 *
 * <p>Accounting model (realized flow economics, deliberately simple
 * and stated): internalized client flow captures the street half
 * spread minus whatever improvement was given back
 * ({@code notional·(halfSpread − improvement)/1e4}); routed flow
 * captures nothing (it went to the street); hedge executions cost
 * their all-in bps; router allocations cost their blended expected
 * bps. Inventory MARK-TO-MARKET P&amp;L is deliberately out of scope —
 * that is the risk report's domain ({@code CentralRiskBook.report}),
 * and mixing realized spread economics with unrealized inventory marks
 * is how desks fool themselves. All notionals positive, all bps
 * non-negative, book-currency units. Persistable via
 * {@code writeState/readState} ({@code persist.Checkpoint} section);
 * deterministic, single-threaded, research/warm lane.</p>
 */
public final class CrbPnlLedger {

    private double spreadCaptured;
    private double improvementPaid;
    private double hedgeCost;
    private double routerCost;
    private long internalizations;
    private long hedges;

    /**
     * Records one internalization decision's economics.
     *
     * @param internalizedNotional |notional| kept on the book, ≥ 0
     * @param halfSpreadBps        the street half spread saved, &gt; 0
     * @param improvementBps       improvement given to the client, ≥ 0
     *                             and ≤ halfSpreadBps
     */
    public void onInternalized(double internalizedNotional, double halfSpreadBps,
                               double improvementBps) {
        requireNonNegative(internalizedNotional, "internalizedNotional");
        if (!(halfSpreadBps > 0) || halfSpreadBps == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("halfSpreadBps must be positive and finite");
        }
        requireNonNegative(improvementBps, "improvementBps");
        if (improvementBps > halfSpreadBps) {
            throw new IllegalArgumentException("improvement " + improvementBps
                    + " exceeds the half spread " + halfSpreadBps
                    + " — the desk would be paying clients to trade");
        }
        if (internalizedNotional == 0) {
            return;                        // fully routed decision: no economics
        }
        spreadCaptured += internalizedNotional * (halfSpreadBps - improvementBps) / 1e4;
        improvementPaid += internalizedNotional * improvementBps / 1e4;
        internalizations++;
    }

    /** Convenience: books a whole {@link InternalizationEngine.Decision}. */
    public void onDecision(InternalizationEngine.Decision decision, double halfSpreadBps) {
        onInternalized(Math.abs(decision.internalized()), halfSpreadBps,
                decision.improvementBps());
    }

    /** Records a hedge execution's all-in cost. */
    public void onHedge(double notional, double costBps) {
        requireNonNegative(costBps, "costBps");
        if (!Double.isFinite(notional)) {
            throw new IllegalArgumentException("notional must be finite");
        }
        double n = Math.abs(notional);
        if (n == 0) {
            return;
        }
        hedgeCost += n * costBps / 1e4;
        hedges++;
    }

    /** Records a router allocation's blended expected cost. */
    public void onRoute(double notional, CrbRouter.Allocation allocation) {
        requireNonNegative(notional, "notional");
        routerCost += notional * allocation.expectedCostBps() / 1e4;
    }

    /** Spread captured by internalizing, net of improvement given back. */
    public double spreadCaptured() {
        return spreadCaptured;
    }

    /** Improvement handed to clients — the cost of being worth trading with. */
    public double improvementPaid() {
        return improvementPaid;
    }

    public double hedgeCost() {
        return hedgeCost;
    }

    public double routerCost() {
        return routerCost;
    }

    /**
     * The desk's realized economics: captured spread minus hedging and
     * routing costs. POSITIVE means the netting engine paid for its own
     * risk management — the CRB's entire commercial argument.
     */
    public double netEconomics() {
        return spreadCaptured - hedgeCost - routerCost;
    }

    public long internalizations() {
        return internalizations;
    }

    public long hedges() {
        return hedges;
    }

    // ------------------------------------------------------------------
    // Overnight persistence (persist.Checkpoint section body)
    // ------------------------------------------------------------------

    public void writeState(java.io.DataOutput out) throws java.io.IOException {
        out.writeByte(1);
        out.writeDouble(spreadCaptured);
        out.writeDouble(improvementPaid);
        out.writeDouble(hedgeCost);
        out.writeDouble(routerCost);
        out.writeLong(internalizations);
        out.writeLong(hedges);
    }

    public void readState(java.io.DataInput in) throws java.io.IOException {
        com.quantfinlib.persist.Checkpoint.requireVersion(in, 1, "CrbPnlLedger");
        spreadCaptured = in.readDouble();
        improvementPaid = in.readDouble();
        hedgeCost = in.readDouble();
        routerCost = in.readDouble();
        internalizations = in.readLong();
        hedges = in.readLong();
    }

    private static void requireNonNegative(double x, String name) {
        if (!(x >= 0) || x == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException(name + " must be >= 0 and finite");
        }
    }
}
