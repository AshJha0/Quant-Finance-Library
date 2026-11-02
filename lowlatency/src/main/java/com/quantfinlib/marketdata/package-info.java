/**
 * In-process market data transport: a zero-allocation lane for the hot
 * path and an object-based lane for research and monitoring.
 *
 * <p><b>HFT lane</b>: {@link com.quantfinlib.marketdata.HftMarketDataBus}
 * over {@link com.quantfinlib.marketdata.TickRingBuffer} (lock-free SPSC,
 * strided sequences on separate cache lines, zero allocation, contained
 * listener failures with health reporting) with
 * {@link com.quantfinlib.marketdata.SymbolRegistry} interning symbols to
 * dense int ids, {@link com.quantfinlib.marketdata.QuoteListener} for
 * top-of-book input and {@link com.quantfinlib.marketdata.Nbbo} for the
 * consolidated inside with per-venue staleness. Latency is measured by
 * {@code examples.HftLatencyBenchmark}, which prints the clock resolution
 * alongside its results; see docs/BENCHMARKS.md.</p>
 *
 * <p><b>Convenience lane</b>:
 * {@link com.quantfinlib.marketdata.MarketDataProcessor} (object events,
 * String symbols, multi-portfolio mark-to-market) and the generic
 * {@link com.quantfinlib.marketdata.RingBuffer} for research and
 * monitoring.</p>
 *
 * <p><b>3.0 notice.</b> This package moves to the separate artifact
 * {@code quant-finance-library-lowlatency} (module {@code com.quantfinlib.lowlatency})
 * in 3.0; the package name is unchanged, so only your dependency changes,
 * not your imports. See docs/MIGRATION_3.0.md.</p>
 */
package com.quantfinlib.marketdata;
