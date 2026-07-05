/**
 * SBE-style binary wire codecs and channel adapters — the professional-grade
 * alternative to the text edges (JSON WebSocket in, FIX tag-value out):
 * {@link com.quantfinlib.sbe.TradeFlyweight},
 * {@link com.quantfinlib.sbe.OrderFlyweight} and
 * {@link com.quantfinlib.sbe.QuoteFlyweight} encode/decode at fixed buffer
 * offsets with zero allocation, zero parsing and zero copying;
 * {@link com.quantfinlib.sbe.BinaryMarketDataClient} feeds the
 * {@code HftMarketDataBus} from a binary stream and
 * {@link com.quantfinlib.sbe.BinaryOrderPublisher}/{@link com.quantfinlib.sbe.BinaryOrderReceiver}
 * carry gateway orders over a binary channel. Wire layouts are documented on
 * each flyweight; symbol ids are part of the wire contract.
 *
 * <p>See {@code docs/ULTRA_LOW_LATENCY.md} for where this sits in the
 * latency stack.</p>
 */
package com.quantfinlib.sbe;
