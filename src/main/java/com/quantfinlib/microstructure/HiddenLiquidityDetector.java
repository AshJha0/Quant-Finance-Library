package com.quantfinlib.microstructure;

/**
 * Hidden-liquidity / iceberg detection from the lit tape. Displayed size is
 * only part of what rests at a price — icebergs show a small tip and reload,
 * and hidden/midpoint orders don't show at all. You can't see them, but you
 * can <em>infer</em> them from a tell: <b>a level that trades more than it
 * ever displayed, and keeps quoting.</b>
 *
 * <p>The sound per-print signature: <b>a single execution larger than the
 * size displayed at that moment.</b> Displayed liquidity cannot fill more
 * than it shows, so the excess in that one print necessarily executed
 * against hidden size at the level. (A cumulative executed-vs-displayed
 * comparison is NOT sound at L2: a busy level legitimately trades many
 * times its instantaneous display through ordinary adds — that
 * formulation false-flags normal flow.) Per level, the detector keeps an
 * EWMA of the print/displayed ratio at those hidden events;
 * {@link #hiddenMultiplier} ≈ 1 means "what you see is what's there," 3
 * means "≈3× the tip is likely lurking."</p>
 *
 * <p>Complements {@code execution.VenueScorecard}, which learns dark-venue
 * liquidity by probing; this one infers <em>lit-venue</em> hidden size
 * without sending an order, so an execution algo can size a child against
 * the true depth rather than the tip. <b>Cross-asset</b>: the
 * trades-more-than-displayed-and-keeps-quoting tell is the same on an
 * equity exchange level and an FX ECN level (icebergs are standard on
 * both); sizes are longs, levels are dense tick indices, same convention
 * as the books. Zero allocation, single writer.</p>
 */
public final class HiddenLiquidityDetector {

    private final int levels;
    private final double alpha;

    private final long[] displayed;        // last seen displayed size per level
    private final double[] refillRatioEwma;// EWMA of print/displayed at hidden events
    private final long[] refillObs;

    /**
     * @param levels number of price levels tracked (dense tick indices)
     * @param alpha  EWMA weight on each refill observation, e.g. 0.2
     */
    public HiddenLiquidityDetector(int levels, double alpha) {
        if (levels < 1 || alpha <= 0 || alpha > 1) {
            throw new IllegalArgumentException("need levels >= 1, alpha in (0,1]");
        }
        this.levels = levels;
        this.alpha = alpha;
        this.displayed = new long[levels];
        this.refillRatioEwma = new double[levels];
        this.refillObs = new long[levels];
    }

    public HiddenLiquidityDetector(int levels) {
        this(levels, 0.2);
    }

    // ------------------------------------------------------------------
    // Feed
    // ------------------------------------------------------------------

    /** The displayed size now standing at {@code level}. */
    public void onDisplayed(int level, long size) {
        displayed[level] = Math.max(0, size);
    }

    /**
     * One trade print of {@code qty} at {@code level}, compared against the
     * size displayed at that moment. A print exceeding the display is the
     * hidden-liquidity event: the overflow could only have filled against
     * unseen size. The EWMA seeds from the first observation — a ratio's
     * meaningful floor is 1.0, so ramping up from 0 would under-register a
     * genuine single event.
     */
    public void onExecution(int level, long qty) {
        if (qty <= 0) {
            return;
        }
        long shown = displayed[level];
        if (shown > 0 && qty > shown) {
            double ratio = (double) qty / shown;
            refillRatioEwma[level] = refillObs[level] == 0
                    ? ratio
                    : refillRatioEwma[level] + alpha * (ratio - refillRatioEwma[level]);
            refillObs[level]++;
        }
    }

    /** The level fully cleared (best moved away / all pulled). */
    public void onLevelCleared(int level) {
        displayed[level] = 0;
    }

    // ------------------------------------------------------------------
    // Inference
    // ------------------------------------------------------------------

    /**
     * Estimated ratio of true resting size to displayed size at a level:
     * 1 = no hidden liquidity detected, {@code >1} = likely iceberg. Uses
     * the EWMA refill ratio, falling back to 1 before any evidence.
     */
    public double hiddenMultiplier(int level) {
        return refillObs[level] == 0 ? 1.0 : Math.max(1.0, refillRatioEwma[level]);
    }

    /**
     * Estimated total resting size at a level = displayed × hidden
     * multiplier — the depth an execution algo should size against, not the
     * visible tip.
     */
    public double estimatedTrueDepth(int level) {
        return displayed[level] * hiddenMultiplier(level);
    }

    /** True once a level has shown iceberg behavior at least once. */
    public boolean isIceberg(int level) {
        return refillObs[level] > 0 && refillRatioEwma[level] > 1.0;
    }

    public long refillObservations(int level) {
        return refillObs[level];
    }

    public int levels() {
        return levels;
    }
}
