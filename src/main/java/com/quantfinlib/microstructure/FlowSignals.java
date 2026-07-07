package com.quantfinlib.microstructure;

/**
 * Streaming order-flow signals for short-horizon execution decisions: the
 * three imbalances an execution engine reads before crossing a spread —
 * cross-asset (equity ticks or raw FX rates; see the two onQuote entry
 * points) —
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

    // Previous inside quote. Prices are doubles so one implementation serves
    // both markets: equity integer ticks are exact in a double (<= 2^53), so
    // the tick-based API below delegates here with identical semantics, and
    // FX raw rates feed the double API directly.
    private double prevBid;
    private long prevBidSize;
    private double prevAsk;
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
     * after the last venue on a side drops) and non-dealable prices (NaN,
     * zero, negative, infinite — placeholder sentinels) are treated as a
     * signal GAP: queue imbalance reads 0 and no OFI contribution is booked
     * — a feed artifact must not look like an aggressive sweep. The next
     * two-sided dealable quote re-seeds the OFI baseline. This is the same
     * gate {@code SignalEngine} applies, so both classify identically.</p>
     */
    public void onQuote(int bidTick, long bidSz, int askTick, long askSz, long timestampNanos) {
        onQuote((double) bidTick, bidSz, (double) askTick, askSz, timestampNanos);
    }

    /**
     * Inside-quote update on raw double prices — the cross-asset entry
     * point (FX rates, or anything not tick-gridded). Same semantics as the
     * tick overload: price comparisons drive the OFI legs, so any monotonic
     * price representation works.
     */
    public void onQuote(double bid, long bidSz, double ask, long askSz, long timestampNanos) {
        quoteCount++;
        // Dealable-price gate (!(x > 0) also catches NaN): a zero/infinite
        // placeholder price with positive sizes must not book phantom OFI
        // legs (bid 0 < prevBid reads as an aggressive sell) or latch its
        // sizes into queueImbalance.
        if (bidSz <= 0 || askSz <= 0
                || !(bid > 0) || !(ask > 0)
                || bid == Double.POSITIVE_INFINITY || ask == Double.POSITIVE_INFINITY) {
            bidSize = 0;
            askSize = 0;
            hasQuote = false;              // gap: don't book flow off a sentinel
            return;
        }
        this.bidSize = bidSz;
        this.askSize = askSz;
        if (hasQuote) {
            double e = 0;
            if (bid > prevBid) {
                e += bidSz;
            } else if (bid == prevBid) {
                e += bidSz - prevBidSize;
            } else {
                e -= prevBidSize;
            }
            if (ask < prevAsk) {
                e -= askSz;
            } else if (ask == prevAsk) {
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
        prevBid = bid;
        prevBidSize = bidSz;
        prevAsk = ask;
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

    // Same half-life algebra as MathUtils.decayFactor, kept local with tau
    // (= halfLife/ln2) precomputed in the constructor: one divide per event
    // on this hot path instead of a multiply and a divide.
    private double decayFactor(long lastTime, long now) {
        long dt = now - lastTime;
        return dt <= 0 ? 1.0 : Math.exp(-dt / tauNanos);
    }
}
