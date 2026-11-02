/**
 * Technical indicators in batch and streaming form.
 * {@link com.quantfinlib.indicators.Indicators}
 * (21 batch indicators over primitive arrays, NaN warm-ups) and
 * {@link com.quantfinlib.indicators.StreamingIndicators} (O(1)-per-tick
 * incremental versions for live/HFT strategies). Parity tests check
 * that the two produce identical values, so a strategy backtested on batch
 * arrays behaves the same on streaming updates.
 */
@NullMarked
package com.quantfinlib.indicators;

import org.jspecify.annotations.NullMarked;
