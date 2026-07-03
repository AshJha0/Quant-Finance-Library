package com.fdequant.marketdata;

/** A tick / trade print. Timestamp in nanoseconds for latency measurement. */
public record MarketDataEvent(String symbol, double price, double volume, long timestampNanos) {
}
