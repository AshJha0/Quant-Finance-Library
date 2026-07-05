/**
 * Built-in {@link com.quantfinlib.backtest.TradingStrategy} implementations:
 * SMA/EMA crossovers, RSI mean reversion, MACD signal-line cross, and
 * Bollinger band reversion. All precompute indicators in
 * {@code init(BarSeries)} and are allocation-free per bar; use them as
 * baselines and as templates for custom strategies.
 */
package com.quantfinlib.backtest.strategies;
