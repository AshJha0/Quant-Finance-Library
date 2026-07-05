/**
 * Multi-asset backtesting:
 * {@link com.quantfinlib.backtest.portfolio.PortfolioBacktester} rebalances
 * long/short weight targets from a
 * {@link com.quantfinlib.backtest.portfolio.PortfolioStrategy} with
 * commission and turnover tracking (input series must be index-aligned —
 * see {@code data.SeriesAligner});
 * {@link com.quantfinlib.backtest.portfolio.PositionSizing} supplies Kelly,
 * fixed-fractional risk, inverse-volatility weights and vol-target leverage.
 */
package com.quantfinlib.backtest.portfolio;
