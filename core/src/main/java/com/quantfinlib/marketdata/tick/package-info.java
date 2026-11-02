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
 *
 * <p><b>Moves in 4.0.</b> This package moves, with its name unchanged, to
 * the reference-tier artifact {@code quant-finance-library-lowlatency}
 * (module {@code com.quantfinlib.lowlatency}), so the whole {@code
 * marketdata} hierarchy lives in one module. Add that dependency; imports do
 * not change. See docs/DESIGN_4.0.md and docs/MIGRATION_4.0.md.</p>
 */
@NullMarked
package com.quantfinlib.marketdata.tick;

import org.jspecify.annotations.NullMarked;
