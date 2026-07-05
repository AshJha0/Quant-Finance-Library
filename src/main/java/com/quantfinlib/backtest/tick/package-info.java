/**
 * Event-driven tick-level backtesting — the level below bars, where queue
 * position decides whether a passive order actually trades:
 * {@link com.quantfinlib.backtest.tick.TickBacktester} replays QFLT tick
 * files through a {@link com.quantfinlib.backtest.tick.TickStrategy} with
 * microstructure-aware fills (market orders pay half the spread; limit
 * orders fill on trade-through or earn fills print-by-print against a
 * simulated queue; optional tick-size grid for real-world prices; no fills
 * against the triggering print).
 */
package com.quantfinlib.backtest.tick;
