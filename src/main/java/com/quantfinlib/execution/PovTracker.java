package com.quantfinlib.execution;

/**
 * POV (percentage-of-volume) execution tracker: the streaming counterpart of
 * the precomputed {@link TwapScheduler}/{@link VwapScheduler} schedules.
 * POV cannot be prescheduled — it chases realized market volume — so this
 * class maintains the participation ledger live: feed it market prints and
 * own fills, and it answers "how many shares am I allowed to send right
 * now" ({@link #dueQuantity}).
 *
 * <p>The participation rate {@code p} is a share of total traded volume,
 * ours included (the industry definition). With {@code V} the volume
 * printed by others ({@link #onMarketVolume}, which must exclude our fills),
 * executing {@code x} gives a share {@code x / (x + V)}; solving
 * {@code x / (x + V) = p} gives the target {@code x = p / (1 - p) * V}.
 * The naive target {@code p * V} realises only {@code p / (1 + p)}.</p>
 *
 * <p>Sent-but-unfilled children count against the target: report them with
 * {@link #onChildSent} and their unfilled remainders with
 * {@link #onChildDone}, so a resting child is not re-sent. Child sizes are
 * clamped to {@code [minSlice, maxSlice]}: the minimum suppresses dribble
 * orders, the maximum caps signalling.</p>
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
    private long open;

    /**
     * @param parentQty     total shares to execute
     * @param participation target share of total volume in (0, 1), e.g. 0.1 = 10%
     * @param minSlice      smallest child worth sending (0 = no minimum)
     * @param maxSlice      largest child ever sent (caps information leakage)
     */
    public PovTracker(long parentQty, double participation, long minSlice, long maxSlice) {
        if (parentQty <= 0 || !(participation > 0 && participation < 1)) {
            throw new IllegalArgumentException("need parentQty > 0, participation in (0,1)");
        }
        if (minSlice < 0 || maxSlice < Math.max(1, minSlice)) {
            throw new IllegalArgumentException("need 0 <= minSlice <= maxSlice");
        }
        this.parentQty = parentQty;
        this.participation = participation;
        this.minSlice = minSlice;
        this.maxSlice = maxSlice;
    }

    /** A market trade print that was not our fill. */
    public void onMarketVolume(long qty) {
        if (qty > 0) {
            marketVolume += qty;
        }
    }

    /** Our own child fill (do not also feed it to {@link #onMarketVolume}). */
    public void onExecuted(long qty) {
        if (qty > 0) {
            executed += qty;
            open = Math.max(0, open - qty);
        }
    }

    /** A child of {@code qty} was sent and is working. */
    public void onChildSent(long qty) {
        if (qty > 0) {
            open += qty;
        }
    }

    /** A child ended with {@code unfilledQty} never executed. */
    public void onChildDone(long unfilledQty) {
        if (unfilledQty > 0) {
            open = Math.max(0, open - unfilledQty);
        }
    }

    /** Sent child quantity not yet filled or reported done. */
    public long openQuantity() {
        return open;
    }

    /** Target executed quantity for the volume seen so far: {@code p / (1 - p) * V}. */
    public long targetQuantity() {
        // The 1e-6 absorbs binary rounding (0.1 / 0.9 * 900000 must give 100000).
        return (long) Math.floor(participation / (1 - participation) * marketVolume + 1e-6);
    }

    /**
     * Shares to send now to restore the target participation: the behind-
     * schedule quantity, clamped to the slice bounds and the parent
     * remainder. Returns 0 while within {@code minSlice} of schedule (or
     * when done) — poll it on every print, act when it's positive.
     */
    public long dueQuantity() {
        long remaining = parentQty - executed - open;
        if (remaining <= 0) {
            return 0;
        }
        long behind = targetQuantity() - executed - open;
        if (behind < Math.max(1, minSlice)) {
            return 0;
        }
        return Math.min(Math.min(behind, maxSlice), remaining);
    }

    /** Realised share of total volume, {@code executed / (executed + V)}; NaN before any volume. */
    public double realizedParticipation() {
        long total = executed + marketVolume;
        return total == 0 ? Double.NaN : (double) executed / total;
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
