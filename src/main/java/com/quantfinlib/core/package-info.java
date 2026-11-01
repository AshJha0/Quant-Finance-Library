/**
 * Core bar and bar-series types used by every analytics package.
 * {@link com.quantfinlib.core.Bar} (immutable OHLCV
 * bar) and {@link com.quantfinlib.core.BarSeries} (cache-friendly
 * structure-of-arrays time series with zero-copy array accessors, returns,
 * and train/test slicing). The accessors return the internal arrays without
 * copying; callers must not modify them. Every analytics module consumes these; nothing in
 * this package depends on anything else.
 *
 * <p>See {@code docs/ARCHITECTURE.md} for the full package map.</p>
 */
@NullMarked
package com.quantfinlib.core;

import org.jspecify.annotations.NullMarked;
