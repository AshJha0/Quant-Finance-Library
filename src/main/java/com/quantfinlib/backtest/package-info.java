/**
 * Bar-based backtesting. {@link com.quantfinlib.backtest.Backtester} is the
 * classic engine (fills at the close, intrabar gap-aware stops);
 * {@link com.quantfinlib.backtest.ExecutionAwareBacktester} upgrades signals
 * to parent orders worked through a pluggable
 * {@link com.quantfinlib.backtest.ExecutionModel}
 * ({@link com.quantfinlib.backtest.InstantExecution},
 * {@link com.quantfinlib.backtest.SorExecution},
 * {@link com.quantfinlib.backtest.IcebergExecution},
 * {@link com.quantfinlib.backtest.LastLookExecution} — FX last-look rejects
 * on adverse hold-window moves) with multi-bar fills and
 * one-call TCA per {@link com.quantfinlib.backtest.ParentOrder};
 * {@link com.quantfinlib.backtest.ExecutionAlgoBacktester} answers the
 * execution desk's own question — which benchmark algorithm, at what
 * cost — by replaying {@code execution.BenchmarkExecutor} over a
 * session's bars and grading it TCA-style (shortfall vs arrival,
 * slippage vs session VWAP), with its simplifications stated in the
 * javadoc. {@link com.quantfinlib.backtest.BenchmarkComparison} scores a
 * strategy the way an allocator does — alpha, beta, tracking error,
 * information ratio, up/down capture against a benchmark — and
 * {@link com.quantfinlib.backtest.DrawdownAnalytics} reports drawdown
 * STRUCTURE (episode depth and duration, time under water), because the
 * number that fires clients is how long the pain lasted, not just how
 * deep it went. Sub-packages: {@code strategies} (built-ins),
 * {@code validation} (walk-forward, purged K-fold, CSCV overfit
 * probability, deflated Sharpe), {@code portfolio} (multi-asset), and
 * {@code tick} (event-driven tick-level engine).
 */
package com.quantfinlib.backtest;
