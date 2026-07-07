package com.quantfinlib.execution;

/**
 * POV (percentage-of-volume) execution tracker: the streaming counterpart of
 * the precomputed {@link TwapScheduler}/{@link VwapScheduler} schedules.
 * POV cannot be prescheduled — it chases realized market volume — so this
 * class maintains the participation ledger live: feed it market prints and
 * own fills, and it answers "how many shares am I allowed to send right
 * now" ({@link #dueQuantity}).
 *
 * <p>The target is {@code executed ≈ participation × marketVolume}, where
 * {@code marketVolume} excludes our own fills (participation is measured
 * against <em>other people's</em> trading, else the algo chases itself:
 * p/(1+p) instead of p). Child sizes are clamped to
 * {@code [minSlice, maxSlice]} — the minimum suppresses dribble orders, the
 * maximum caps signaling risk.</p>
 *
 * <p>Primitives only, zero allocation, single-writer.</p>
 */
public final class PovTracker {

    private final long parentQty;
    private final double participation;
    private final long minSlice;
    private final long maxSlice;

    private long marketVolume;
    private long executed;

    /**
     * @param parentQty     total shares to execute
     * @param participation target participation rate in (0, 1], e.g. 0.1 = 10%
     * @param minSlice      smallest child worth sending (0 = no minimum)
     * @param maxSlice      largest child ever sent (caps information leakage)
     */
    public PovTracker(long parentQty, double participation, long minSlice, long maxSlice) {
        if (parentQty <= 0 || participation <= 0 || participation > 1) {
            throw new IllegalArgumentException("need parentQty > 0, participation in (0,1]");
        }
        if (minSlice < 0 || maxSlice < Math.max(1, minSlice)) {
            throw new IllegalArgumentException("need 0 <= minSlice <= maxSlice");
        }
        this.parentQty = parentQty;
        this.participation = participation;
        this.minSlice = minSlice;
        this.maxSlice = maxSlice;
    }

    /** A market trade print that was NOT our fill. */
    public void onMarketVolume(long qty) {
        if (qty > 0) {
            marketVolume += qty;
        }
    }

    /** Our own child fill (do not also feed it to {@link #onMarketVolume}). */
    public void onExecuted(long qty) {
        if (qty > 0) {
            executed += qty;
        }
    }

    /**
     * Shares to send now to restore the target participation: the behind-
     * schedule quantity, clamped to the slice bounds and the parent
     * remainder. Returns 0 while within {@code minSlice} of schedule (or
     * when done) — poll it on every print, act when it's positive.
     */
    public long dueQuantity() {
        long remaining = parentQty - executed;
        if (remaining <= 0) {
            return 0;
        }
        long target = (long) (participation * marketVolume);
        long behind = target - executed;
        if (behind < Math.max(1, minSlice)) {
            return 0;
        }
        return Math.min(Math.min(behind, maxSlice), remaining);
    }

    /** Realized participation so far vs other-flow volume (NaN before any print). */
    public double realizedParticipation() {
        return marketVolume == 0 ? Double.NaN : (double) executed / marketVolume;
    }

    public long executed() {
        return executed;
    }

    public long remaining() {
        return parentQty - executed;
    }

    public long marketVolume() {
        return marketVolume;
    }

    public boolean done() {
        return executed >= parentQty;
    }
}
