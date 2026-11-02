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
 *
 * <p><b>3.0 notice.</b> This package moves to the separate artifact
 * {@code quant-finance-library-lowlatency} (module {@code com.quantfinlib.lowlatency})
 * in 3.0; the package name is unchanged, so only your dependency changes,
 * not your imports. See docs/MIGRATION_3.0.md.</p>
 */
package com.quantfinlib.feed;
