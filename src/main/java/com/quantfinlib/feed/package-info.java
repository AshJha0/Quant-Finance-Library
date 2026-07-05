/**
 * Live market data over WebSocket (pure JDK {@code java.net.http}):
 * {@link com.quantfinlib.feed.WebSocketFeed} publishes parsed trades into the
 * {@code HftMarketDataBus} with automatic reconnection, and
 * {@link com.quantfinlib.feed.BinanceTradeParser} is the reference
 * {@link com.quantfinlib.feed.FeedParser} (one small class per additional
 * exchange). Text-protocol transport: convenient and internet-grade; see the
 * {@code sbe} package for the binary counterpart.
 */
package com.quantfinlib.feed;
