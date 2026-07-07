package com.quantfinlib.microstructure;

/**
 * Queue position estimation from <b>L2</b> data — for when you don't have
 * the L3 feed that {@code marketdata.L3BookBuilder} needs to track position
 * exactly. With only aggregated level sizes you can't see individual orders,
 * so this estimator maintains a probabilistic band on where a passive order
 * sits, updated with the standard assumptions:
 *
 * <ul>
 *   <li><b>On join</b> — you rest at the back: shares-ahead = the level's
 *       current displayed size;</li>
 *   <li><b>Executions at your level</b> — trades hit the front (price-time
 *       priority), so they reduce shares-ahead one-for-one;</li>
 *   <li><b>Cancels at your level</b> — the hard part: a decrease in level
 *       size that isn't a trade is a cancel, and it could be ahead of you
 *       or behind. The literature's workable assumption is <b>pro-rata</b>:
 *       a cancel removes shares-ahead in proportion to the fraction of the
 *       queue that is ahead of you. That gives an unbiased estimate without
 *       L3 — the {@code ahead} field is the expected value, and
 *       {@link #fillProbability} turns it into a fill likelihood via
 *       {@link QueueModel}.</li>
 * </ul>
 *
 * <p>One passive order per instance (cheap — make one per working child).
 * Feed it the level's size changes split into the trade part and the total
 * part; it infers cancels as the residual. <b>Feed ordering contract</b>:
 * report each trade via {@link #onTrade} BEFORE the depth update that
 * reflects it, and give {@link #onLevelResize} sizes net of trades already
 * reported — otherwise the same execution is counted once as a trade and
 * again as a cancel, and shares-ahead falls twice per fill. Zero
 * allocation, single writer.</p>
 *
 * <p><b>Cross-asset</b>: applies to any price-time-priority level — equity
 * exchange books and FX ECN/matching books alike (sizes are just longs; an
 * FX "share" is a unit of base currency). It does NOT apply to FX LP quote
 * streams, which have no queues — that side is {@code fx.LpRouter}'s
 * last-look world.</p>
 */
public final class QueuePositionEstimator {

    private double ahead;          // expected shares ahead of us
    private double aheadAtJoin;    // shares ahead when we joined (progress base)
    private double levelSize;      // current displayed size at our level
    private long ownQty;
    private boolean active;

    /**
     * Join the back of a level currently displaying {@code levelSize} shares
     * (before our order is added).
     */
    public void join(long levelSize, long ownQty) {
        if (levelSize < 0 || ownQty <= 0) {
            throw new IllegalArgumentException("need levelSize >= 0, ownQty > 0");
        }
        this.ahead = levelSize;
        this.aheadAtJoin = levelSize;
        this.levelSize = levelSize + ownQty;
        this.ownQty = ownQty;
        this.active = true;
    }

    /**
     * A trade executed at our level: it consumed {@code tradedQty} from the
     * front, so shares-ahead drops by that much (clamped at 0 — once the
     * front reaches us we start filling).
     */
    public void onTrade(long tradedQty) {
        if (!active || tradedQty <= 0) {
            return;
        }
        ahead = Math.max(0, ahead - tradedQty);
        levelSize = Math.max(ownQty, levelSize - tradedQty);
    }

    /**
     * The level's displayed size changed to {@code newLevelSize} for a
     * reason other than a trade — i.e. cancels (net of any adds behind us).
     * The removed quantity is attributed pro-rata: the fraction of the
     * queue ahead of us is {@code ahead / (levelSize − ownQty)}, so that
     * fraction of the cancel came from ahead.
     */
    public void onLevelResize(long newLevelSize) {
        if (!active) {
            return;
        }
        double removed = levelSize - ownQty - Math.max(0, newLevelSize - ownQty);
        // Only cancels (size shrank among the OTHER orders) move our
        // estimate; adds land behind us and don't change shares-ahead.
        if (removed > 0) {
            double others = levelSize - ownQty;
            double fracAhead = others > 0 ? ahead / others : 0;
            ahead = Math.max(0, ahead - removed * fracAhead);
        }
        levelSize = Math.max(ownQty, newLevelSize);
    }

    /** Expected shares ahead of our order right now. */
    public double sharesAhead() {
        return ahead;
    }

    /**
     * Fill probability over a horizon in which {@code expectedTradedQty}
     * shares are expected to execute at this level — {@link QueueModel}
     * applied to the estimated position.
     */
    public double fillProbability(double expectedTradedQty) {
        return QueueModel.fillProbability(Math.round(ahead), ownQty, expectedTradedQty);
    }

    /**
     * Queue progress since joining: 0 right after {@link #join}, 1 when the
     * whole queue that was ahead of us has drained. Measured against the
     * shares-ahead AT JOIN — the only meaningful baseline.
     */
    public double queueProgress() {
        return aheadAtJoin <= 0 ? 1.0 : Math.max(0, 1 - ahead / aheadAtJoin);
    }

    public boolean active() {
        return active;
    }

    public long ownQty() {
        return ownQty;
    }

    /** Order left the book (filled/cancelled). */
    public void close() {
        active = false;
    }
}
