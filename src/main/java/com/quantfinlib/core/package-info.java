/**
 * Core market data types: {@link com.quantfinlib.core.Bar} (immutable OHLCV
 * bar) and {@link com.quantfinlib.core.BarSeries} (cache-friendly
 * structure-of-arrays time series with zero-copy array accessors, returns,
 * and train/test slicing). Every analytics module consumes these; nothing in
 * this package depends on anything else.
 *
 * <p>See {@code docs/ARCHITECTURE.md} for the full package map.</p>
 */
package com.quantfinlib.core;
