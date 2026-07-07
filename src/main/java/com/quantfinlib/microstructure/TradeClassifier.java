package com.quantfinlib.microstructure;

/**
 * Trade aggressor classification (Lee-Ready, 1991): the missing glue for
 * feeds that print trades without saying who initiated. {@link FlowSignals}
 * and {@code SignalEngine.onTrade} need {@code buyAggressor}; consolidated
 * tapes and many FX feeds don't carry it, so it must be inferred:
 *
 * <ol>
 *   <li><b>Quote rule</b> — a trade at or above the ask was buyer-initiated
 *       (someone lifted the offer); at or below the bid, seller-initiated.
 *       Between the quotes, above the mid leans buy, below leans sell;</li>
 *   <li><b>Tick test</b> (exactly at the mid, or no quote) — an uptick from
 *       the previous trade price is a buy, a downtick a sell, an equal
 *       price repeats the last classification (the "zero-tick" rule).</li>
 * </ol>
 *
 * <p>Classification accuracy of this scheme is ~85% on modern equity data
 * and similar on FX ECN prints — imperfect by construction (that's the
 * literature's number, not a defect), which is why the imbalance signals
 * it feeds are exponentially decayed averages rather than per-trade
 * truths. One instance per symbol; cross-asset (raw double prices); zero
 * allocation, single writer.</p>
 */
public final class TradeClassifier {

    /** Classification results. */
    public static final int BUY = 1;
    public static final int SELL = -1;
    public static final int UNKNOWN = 0;

    private double bid = Double.NaN;
    private double ask = Double.NaN;
    private double lastTradePrice = Double.NaN;
    private int lastClassification = UNKNOWN;

    /** The current inside quote (NaN sides are treated as absent). */
    public void onQuote(double bid, double ask) {
        this.bid = bid;
        this.ask = ask;
    }

    /**
     * Classifies a trade print and remembers it for the tick test.
     * Returns {@link #BUY}, {@link #SELL} or {@link #UNKNOWN} (no quote,
     * no prior trade, or a non-finite price).
     */
    public int classify(double tradePrice) {
        if (!(tradePrice > 0) || tradePrice == Double.POSITIVE_INFINITY) {
            return UNKNOWN;                // non-dealable print: classify nothing
        }
        int result = quoteRule(tradePrice);
        if (result == UNKNOWN) {
            result = tickTest(tradePrice);
        }
        lastTradePrice = tradePrice;
        if (result != UNKNOWN) {
            lastClassification = result;
        }
        return result;
    }

    /** Convenience for {@code SignalEngine.onTrade}: UNKNOWN maps to the last known side. */
    public boolean isBuyAggressor(double tradePrice) {
        int c = classify(tradePrice);
        return (c == UNKNOWN ? lastClassification : c) == BUY;
    }

    private int quoteRule(double price) {
        boolean hasBid = !Double.isNaN(bid);
        boolean hasAsk = !Double.isNaN(ask);
        if (hasAsk && price >= ask) {
            return BUY;
        }
        if (hasBid && price <= bid) {
            return SELL;
        }
        if (hasBid && hasAsk) {
            double mid = 0.5 * (bid + ask);
            if (price > mid) {
                return BUY;
            }
            if (price < mid) {
                return SELL;
            }
        }
        return UNKNOWN;                    // exactly at mid, or no usable quote
    }

    private int tickTest(double price) {
        if (Double.isNaN(lastTradePrice)) {
            return UNKNOWN;
        }
        if (price > lastTradePrice) {
            return BUY;
        }
        if (price < lastTradePrice) {
            return SELL;
        }
        return lastClassification;         // zero-tick: repeat the last side
    }
}
