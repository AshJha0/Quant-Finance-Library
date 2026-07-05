/**
 * Technical analysis engine: {@link com.quantfinlib.indicators.Indicators}
 * (21 batch indicators over primitive arrays, NaN warm-ups) and
 * {@link com.quantfinlib.indicators.StreamingIndicators} (O(1)-per-tick
 * incremental versions for live/HFT strategies). The two are verified
 * <b>bit-identical</b> by parity tests, so a strategy backtested on batch
 * arrays behaves exactly the same running live on streaming updates.
 */
package com.quantfinlib.indicators;
