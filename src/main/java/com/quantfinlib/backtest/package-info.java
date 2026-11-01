/**
 * Bar-based backtesting engines, execution models and performance analytics.
 * {@link com.quantfinlib.backtest.Backtester} is the single-instrument
 * engine (fills at the next bar's open by default, see
 * {@link com.quantfinlib.backtest.FillPolicy}; intrabar gap-aware stops);
 * {@link com.quantfinlib.backtest.ExecutionAwareBacktester} upgrades signals
 * to parent orders worked through a pluggable
 * {@link com.quantfinlib.backtest.ExecutionModel}
 * ({@link com.quantfinlib.backtest.InstantExecution},
 * {@link com.quantfinlib.backtest.SorExecution},
 * {@link com.quantfinlib.backtest.IcebergExecution},
 * {@link com.quantfinlib.backtest.LastLookExecution} — FX last-look rejects
 * on adverse hold-window moves) with multi-bar fills and
 * one-call TCA per {@link com.quantfinlib.backtest.ParentOrder};
 * {@link com.quantfinlib.backtest.ExecutionAlgoBacktester} compares
 * benchmark algorithms by cost, replaying {@code execution.BenchmarkExecutor} over a
 * session's bars and grading it TCA-style (shortfall vs arrival,
 * slippage vs session VWAP), with its simplifications stated in the
 * javadoc. {@link com.quantfinlib.backtest.BenchmarkComparison} reports
 * alpha, beta, tracking error, information ratio and up/down capture
 * against a benchmark, and
 * {@link com.quantfinlib.backtest.DrawdownAnalytics} reports drawdown
 * structure: episode depth and duration and time under water.
 * Sub-packages: {@code strategies} (built-ins),
 * {@code validation} (walk-forward, purged K-fold, CSCV overfit
 * probability, deflated Sharpe), {@code portfolio} (multi-asset), and
 * {@code tick} (event-driven tick-level engine).
 */
package com.quantfinlib.backtest;
