/**
 * Bar-based backtesting. {@link com.quantfinlib.backtest.Backtester} is the
 * classic engine (fills at the close, intrabar gap-aware stops);
 * {@link com.quantfinlib.backtest.ExecutionAwareBacktester} upgrades signals
 * to parent orders worked through a pluggable
 * {@link com.quantfinlib.backtest.ExecutionModel}
 * ({@link com.quantfinlib.backtest.InstantExecution},
 * {@link com.quantfinlib.backtest.SorExecution},
 * {@link com.quantfinlib.backtest.IcebergExecution}) with multi-bar fills and
 * one-call TCA per {@link com.quantfinlib.backtest.ParentOrder}.
 * Sub-packages: {@code strategies} (built-ins), {@code validation}
 * (walk-forward, deflated Sharpe), {@code portfolio} (multi-asset), and
 * {@code tick} (event-driven tick-level engine).
 */
package com.quantfinlib.backtest;
