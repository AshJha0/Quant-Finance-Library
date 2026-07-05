/**
 * Market data transport, two lanes:
 *
 * <p><b>HFT lane</b> — {@link com.quantfinlib.marketdata.HftMarketDataBus}
 * over {@link com.quantfinlib.marketdata.TickRingBuffer} (lock-free SPSC,
 * padded sequences, zero allocation; measured p50 ≈ 204 ns
 * publish-to-strategy) with {@link com.quantfinlib.marketdata.SymbolRegistry}
 * interning symbols to dense int ids.</p>
 *
 * <p><b>Convenience lane</b> —
 * {@link com.quantfinlib.marketdata.MarketDataProcessor} (object events,
 * String symbols, multi-portfolio mark-to-market) for research and
 * monitoring where nanoseconds don't matter.</p>
 */
package com.quantfinlib.marketdata;
