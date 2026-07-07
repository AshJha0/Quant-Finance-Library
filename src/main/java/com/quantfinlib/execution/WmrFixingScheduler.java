package com.quantfinlib.execution;

import java.util.List;

/**
 * Benchmark-fixing execution schedule, WMR-style: orders benchmarked to a
 * fixing (the WM/Refinitiv 4pm London fix and its cousins) are executed by
 * spreading the parent evenly across the fixing's calculation window, so
 * realized cost tracks the benchmark rather than betting against it — the
 * window is 5 minutes for major pairs, and the benchmark is computed from
 * observations inside it, so TWAP-in-window IS the neutral replication.
 *
 * <p>Deliberately excluded, with reasons: executing ahead of the window to
 * "get done early" is pre-hedging risk against the client's benchmark (the
 * conduct the 2013-15 FX fix scandals were about), and skewing inside the
 * window is a bet, not benchmark replication. Anyone who wants a bet can
 * use {@link ImplementationShortfallScheduler} and own it explicitly.</p>
 *
 * <p>Research-lane class (allocates the schedule list); the slices it
 * emits are executed by the hot lane.</p>
 */
public final class WmrFixingScheduler {

    private WmrFixingScheduler() {
    }

    /** The standard WMR calculation window for major pairs. */
    public static final long WINDOW_MILLIS = 5 * 60 * 1_000L;

    /**
     * Even slices across the fixing window. Offsets are relative to the
     * schedule's own start; align slice 0 with the window open (fix time
     * minus half the window).
     *
     * @param totalQty     parent quantity (sliced exactly, largest-remainder)
     * @param windowMillis calculation-window length ({@link #WINDOW_MILLIS} for majors)
     * @param numSlices    child count; more slices = closer benchmark tracking,
     *                     more tickets
     */
    public static List<Slice> schedule(long totalQty, long windowMillis, int numSlices) {
        if (totalQty <= 0 || windowMillis <= 0 || numSlices < 1) {
            throw new IllegalArgumentException(
                    "need totalQty > 0, windowMillis > 0, numSlices >= 1");
        }
        if (numSlices > totalQty) {
            // Even slicing would emit zero-quantity children — venue rejects
            // in the middle of the fixing window. Fail at schedule time.
            throw new IllegalArgumentException(
                    "numSlices (" + numSlices + ") exceeds totalQty (" + totalQty
                            + "): zero-quantity child orders");
        }
        if (windowMillis > Long.MAX_VALUE / numSlices) {
            // Offset arithmetic (windowMillis * i) would overflow and place
            // slices BEFORE the window — the pre-window execution this class
            // exists to refuse.
            throw new IllegalArgumentException("windowMillis too large: " + windowMillis);
        }
        // The schedule IS a TWAP over the window — delegate so the claim
        // "TWAP-in-window is neutral replication" is true by construction
        // and cannot drift from the TWAP implementation.
        return TwapScheduler.schedule(totalQty, windowMillis, numSlices);
    }

    /** {@link #schedule} with the standard 5-minute window. */
    public static List<Slice> schedule(long totalQty, int numSlices) {
        return schedule(totalQty, WINDOW_MILLIS, numSlices);
    }
}
