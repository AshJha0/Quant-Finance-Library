package com.quantfinlib.feed;

/** One trade parsed from an exchange feed message. */
public record FeedTrade(String symbol, double price, double size, long timestampMillis) {
}
