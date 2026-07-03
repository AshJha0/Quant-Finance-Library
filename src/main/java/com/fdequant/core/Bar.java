package com.fdequant.core;

/**
 * Immutable OHLCV bar. Timestamp is epoch milliseconds.
 */
public record Bar(long timestamp, double open, double high, double low, double close, double volume) {

    public Bar {
        if (high < low) {
            throw new IllegalArgumentException("high (" + high + ") < low (" + low + ")");
        }
    }

    public double typicalPrice() {
        return (high + low + close) / 3.0;
    }

    public double range() {
        return high - low;
    }

    public boolean isBullish() {
        return close > open;
    }
}
