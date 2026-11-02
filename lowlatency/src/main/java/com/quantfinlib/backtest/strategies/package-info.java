/**
 * The built-in {@link com.quantfinlib.backtest.TradingStrategy} baseline:
 * {@link com.quantfinlib.backtest.strategies.SmaCrossStrategy}, an SMA
 * crossover that precomputes its indicators in {@code init(BarSeries)} and is
 * allocation-free per bar. Use it as a baseline and as a template.
 *
 * <p>The EMA, RSI, MACD and Bollinger samples that lived here in 2.x were
 * removed in 3.0 (decision D3); copies ship in
 * {@code com.quantfinlib.examples.strategies} in the {@code examples} jar.</p>
 *
 * <p><b>Since 4.0.</b> These sample strategies are not API: they ship in the
 * {@code examples} classifier jar of {@code quant-finance-library-
 * lowlatency} (module {@code com.quantfinlib.lowlatency}), with the package
 * name unchanged. Copy a strategy into your own code, or put the examples
 * jar on the class path. See docs/MIGRATION_4.0.md.</p>
 */
@NullMarked
package com.quantfinlib.backtest.strategies;

import org.jspecify.annotations.NullMarked;
