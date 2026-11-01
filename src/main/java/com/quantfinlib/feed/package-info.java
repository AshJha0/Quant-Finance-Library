/**
 * Market-data transports.
 * {@link com.quantfinlib.feed.WebSocketFeed} publishes parsed trades into the
 * {@code HftMarketDataBus} (pure JDK {@code java.net.http}) with single-epoch
 * reconnection, bounded publish retry and a gap flag;
 * {@link com.quantfinlib.feed.BinanceTradeParser} is the reference
 * {@link com.quantfinlib.feed.FeedParser}.
 * {@link com.quantfinlib.feed.MoldUdp64Receiver} unpacks MoldUDP64
 * datagrams (the transport of Nasdaq ITCH) into sequenced messages with gap
 * detection, retransmit requests and a snapshot fallback. See the
 * {@code sbe} package for the binary order and trade channels.
 */
package com.quantfinlib.feed;
