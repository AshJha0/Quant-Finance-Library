package com.quantfinlib.marketdata;

/**
 * All-primitive top-of-book callback: best bid and offer for one symbol.
 * An absent side is NaN (price) with size 0. Implementations run on the
 * market-data thread and must not block.
 */
@FunctionalInterface
public interface QuoteListener {

    void onQuote(int symbolId, double bid, double bidSize, double ask, double askSize,
                 long timestampNanos);
}
