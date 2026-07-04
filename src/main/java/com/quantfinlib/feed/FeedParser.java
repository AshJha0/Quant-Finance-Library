package com.quantfinlib.feed;

/**
 * Parses one exchange feed message into a trade. Implementations must return
 * {@code null} (never throw) for non-trade messages — subscription acks,
 * heartbeats, or malformed payloads — so a noisy feed cannot kill the
 * adapter.
 */
@FunctionalInterface
public interface FeedParser {

    FeedTrade parseTrade(String message);
}
