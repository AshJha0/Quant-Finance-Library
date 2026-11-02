/**
 * Event-driven tick-level backtesting with queue-aware passive fills.
 * Below the bar level, queue position decides whether a passive order
 * trades:
 * {@link com.quantfinlib.backtest.tick.TickBacktester} replays QFLT tick
 * files through a {@link com.quantfinlib.backtest.tick.TickStrategy} with
 * microstructure-aware fills (market orders pay half the spread; limit
 * orders fill on trade-through or earn fills print-by-print against a
 * simulated queue; optional tick-size grid for real-world prices; no fills
 * against the triggering print).
 *
 * <p><b>Since 4.0.</b> This package is in {@code quant-finance-library-
 * lowlatency} (module {@code com.quantfinlib.lowlatency}), next to {@code
 * marketdata.tick}, whose QFLT files it replays; its name did not change.
 * See docs/MIGRATION_4.0.md.</p>
 */
@NullMarked
package com.quantfinlib.backtest.tick;

import org.jspecify.annotations.NullMarked;
