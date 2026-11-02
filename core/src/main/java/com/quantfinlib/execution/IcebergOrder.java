package com.quantfinlib.execution;

import java.util.SplittableRandom;

/**
 * Iceberg order state machine: shows only a small display tranche of the full
 * quantity and reloads automatically when the visible portion fills. Display
 * sizes can be randomized to make the iceberg harder to detect.
 */
public final class IcebergOrder {

    private final long displayQty;
    private final double randomizePct;
    private final SplittableRandom rnd;
    private long remaining;   // total unexecuted (visible + hidden)
    private long visible;     // currently displayed tranche

    public IcebergOrder(long totalQty, long displayQty) {
        this(totalQty, displayQty, 0, 0);
    }

    /** @param randomizePct display-size jitter, e.g. 0.2 = ±20% (0 = fixed) */
    public IcebergOrder(long totalQty, long displayQty, double randomizePct, long seed) {
        if (totalQty <= 0 || displayQty <= 0) {
            throw new IllegalArgumentException("quantities must be positive");
        }
        this.displayQty = displayQty;
        this.randomizePct = randomizePct;
        this.rnd = new SplittableRandom(seed);
        this.remaining = totalQty;
        this.visible = nextTranche();
    }

    private long nextTranche() {
        long base = displayQty;
        if (randomizePct > 0) {
            base = Math.max(1, Math.round(displayQty * (1 + randomizePct * (2 * rnd.nextDouble() - 1))));
        }
        return Math.min(base, remaining);
    }

    /**
     * Records a fill against the visible tranche. Returns true when the
     * tranche was exhausted and a fresh one was loaded (i.e. the working
     * order should be re-submitted at the back of the queue).
     */
    public boolean onFill(long qty) {
        if (qty <= 0 || qty > visible) {
            throw new IllegalArgumentException("fill " + qty + " exceeds visible " + visible);
        }
        visible -= qty;
        remaining -= qty;
        if (visible == 0 && remaining > 0) {
            visible = nextTranche();
            return true;
        }
        return false;
    }

    public long visibleQty()   { return visible; }
    public long hiddenQty()    { return remaining - visible; }
    public long remainingQty() { return remaining; }

    public boolean isComplete() {
        return remaining == 0;
    }
}
