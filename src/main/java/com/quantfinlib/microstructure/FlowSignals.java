package com.quantfinlib.microstructure;

/**
 * Streaming order-flow signals for short-horizon execution decisions: the
 * three imbalances an equities engine reads before crossing a spread —
 *
 * <ul>
 *   <li><b>Order-flow imbalance (OFI)</b> — Cont/Kukanov/Stoikov best-level
 *       formulation: a bid price/size increase or ask decrease is buying
 *       pressure; the mirror is selling pressure. Exponentially time-decayed
 *       so the signal is a "recent net flow" with a configurable memory;</li>
 *   <li><b>Queue imbalance</b> — {@code (bidSize - askSize)/(bidSize + askSize)}
 *       at the inside, the classic next-tick-direction predictor;</li>
 *   <li><b>Trade imbalance</b> — time-decayed signed aggressor volume over
 *       time-decayed total volume (+1 = all buying, -1 = all selling).</li>
 * </ul>
 *
 * <p>Zero allocation per event, single-writer, primitives only — feed it
 * from the same thread as your book builder or bus callback. Microprice
 * itself lives in {@code pricing.FairValueEngine}; these are its flow-side
 * complements.</p>
 */
public final class FlowSignals {

    private final double tauNanos;

    // Previous inside quote.
    private int prevBidTick = Integer.MIN_VALUE;
    private long prevBidSize;
    private int prevAskTick = Integer.MAX_VALUE;
    private long prevAskSize;
    private boolean hasQuote;

    // Latest inside quote (for queue imbalance).
    private long bidSize;
    private long askSize;

    // Decayed accumulators.
    private double ofi;
    private long ofiTime;
    private double signedVolume;
    private double totalVolume;
    private long tradeTime;

    private long quoteCount;
    private long tradeCount;

    /**
     * @param halfLifeNanos decay half-life for OFI and trade imbalance; e.g.
     *                      500ms = {@code 500_000_000L}. Shorter = twitchier.
     */
    public FlowSignals(long halfLifeNanos) {
        if (halfLifeNanos <= 0) {
            throw new IllegalArgumentException("halfLifeNanos must be positive");
        }
        this.tauNanos = halfLifeNanos / Math.log(2);
    }

    /** 500 ms half-life. */
    public FlowSignals() {
        this(500_000_000L);
    }

    /**
     * Inside-quote update (ticks + sizes). The OFI contribution of this
     * event, per the best-level formulation:
     * bid up/size up ⇒ +, bid down ⇒ −, ask down/size up ⇒ −, ask up ⇒ +.
     *
     * <p>One-sided quotes (either size ≤ 0, e.g. an {@code Nbbo} sentinel
     * after the last venue on a side drops) are treated as a signal GAP:
     * queue imbalance reads 0 and no OFI contribution is booked — a feed
     * artifact must not look like an aggressive sweep. The next two-sided
     * quote re-seeds the OFI baseline.</p>
     */
    public void onQuote(int bidTick, long bidSz, int askTick, long askSz, long timestampNanos) {
        quoteCount++;
        this.bidSize = bidSz;
        this.askSize = askSz;
        if (bidSz <= 0 || askSz <= 0) {
            hasQuote = false;              // gap: don't book flow off a sentinel
            return;
        }
        if (hasQuote) {
            double e = 0;
            if (bidTick > prevBidTick) {
                e += bidSz;
            } else if (bidTick == prevBidTick) {
                e += bidSz - prevBidSize;
            } else {
                e -= prevBidSize;
            }
            if (askTick < prevAskTick) {
                e -= askSz;
            } else if (askTick == prevAskTick) {
                e -= askSz - prevAskSize;
            } else {
                e += prevAskSize;
            }
            ofi = decayed(ofi, ofiTime, timestampNanos) + e;
            ofiTime = timestampNanos;
        } else {
            hasQuote = true;
            ofiTime = timestampNanos;
        }
        prevBidTick = bidTick;
        prevBidSize = bidSz;
        prevAskTick = askTick;
        prevAskSize = askSz;
    }

    /**
     * Trade print with aggressor side: {@code buyAggressor} true when the
     * buyer crossed the spread (trade at/above ask under Lee-Ready).
     */
    public void onTrade(boolean buyAggressor, long quantity, long timestampNanos) {
        tradeCount++;
        double decay = decayFactor(tradeTime, timestampNanos);
        signedVolume = signedVolume * decay + (buyAggressor ? quantity : -quantity);
        totalVolume = totalVolume * decay + quantity;
        tradeTime = timestampNanos;
    }

    /** Time-decayed net order-flow imbalance in shares (+ = buying pressure). */
    public double ofi() {
        return ofi;
    }

    /** Decay-adjusted OFI as of {@code nowNanos} without adding an event. */
    public double ofi(long nowNanos) {
        return decayed(ofi, ofiTime, nowNanos);
    }

    /** Inside-queue imbalance in [-1, 1]; 0 when either side is empty/unset. */
    public double queueImbalance() {
        if (bidSize <= 0 || askSize <= 0) {
            return 0;                      // one-sided book: no imbalance signal
        }
        return (double) (bidSize - askSize) / (bidSize + askSize);
    }

    /** Signed/total decayed aggressor volume in [-1, 1]; 0 before any trade. */
    public double tradeImbalance() {
        return totalVolume <= 0 ? 0 : signedVolume / totalVolume;
    }

    public long quoteCount() {
        return quoteCount;
    }

    public long tradeCount() {
        return tradeCount;
    }

    private double decayed(double value, long lastTime, long now) {
        return value * decayFactor(lastTime, now);
    }

    private double decayFactor(long lastTime, long now) {
        long dt = now - lastTime;
        return dt <= 0 ? 1.0 : Math.exp(-dt / tauNanos);
    }
}
