/**
 * The QFLT binary tick-file format:
 * {@link com.quantfinlib.marketdata.tick.TickFileWriter} records ticks and
 * {@link com.quantfinlib.marketdata.tick.TickFileReader} replays them, as fast
 * as possible or paced, with torn-tail detection.
 *
 * <p>Until 2.4 these types were in {@code com.quantfinlib.data}. In 3.0 they
 * stay in the main artifact, because the stable tick backtester
 * ({@code backtest.tick.TickBacktester}) reads this format; the live-bus
 * recorders that write it ({@code marketdata.TickCapture},
 * {@code marketdata.AsyncTickCapture}) are in the low-latency artifact. The
 * package name matches the plan in docs/MIGRATION_3.0.md, section 8.</p>
 */
@NullMarked
package com.quantfinlib.marketdata.tick;

import org.jspecify.annotations.NullMarked;
