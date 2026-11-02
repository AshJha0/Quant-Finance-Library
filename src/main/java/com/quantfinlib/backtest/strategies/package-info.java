/**
 * Built-in {@link com.quantfinlib.backtest.TradingStrategy} implementations:
 * SMA/EMA crossovers, RSI mean reversion, MACD signal-line cross, and
 * Bollinger band reversion. All precompute indicators in
 * {@code init(BarSeries)} and are allocation-free per bar; use them as
 * baselines and as templates for custom strategies.
 *
 * <p><b>3.0 notice.</b> Only {@link com.quantfinlib.backtest.strategies.SmaCrossStrategy}
 * stays in this package in 3.0. The EMA, RSI, MACD and Bollinger samples are
 * deprecated for removal in 2.4.0; identical copies live in
 * {@code com.quantfinlib.examples.strategies} in the {@code examples} jar.</p>
 */
@NullMarked
package com.quantfinlib.backtest.strategies;

import org.jspecify.annotations.NullMarked;
