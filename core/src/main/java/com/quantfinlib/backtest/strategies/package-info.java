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
 * <p><b>Moves in 4.0.</b> These sample strategies leave the stable jar and
 * ship, with the package name unchanged, in the {@code examples} classifier
 * jar of the reference-tier artifact {@code quant-finance-library-
 * lowlatency} (module {@code com.quantfinlib.lowlatency}). Copy a strategy
 * into your own code, or depend on the examples jar. See docs/DESIGN_4.0.md
 * and docs/MIGRATION_4.0.md.</p>
 */
@NullMarked
package com.quantfinlib.backtest.strategies;

import org.jspecify.annotations.NullMarked;
