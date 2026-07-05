/**
 * Multi-asset backtesting:
 * {@link com.quantfinlib.backtest.portfolio.PortfolioBacktester} rebalances
 * long/short weight targets from a
 * {@link com.quantfinlib.backtest.portfolio.PortfolioStrategy} with
 * commission and turnover tracking (input series must be index-aligned —
 * see {@code data.SeriesAligner}), and in its survivorship-aware overload
 * consumes a {@code data.PointInTimeUniverse} (delistings terminate at the
 * delisting return, mergers convert at deal terms, index drops force sales)
 * plus explicit ex-date cash dividends;
 * {@link com.quantfinlib.backtest.portfolio.CrossSectionalMomentum} is the
 * built-in point-in-time factor strategy (12-1 momentum ranked over the
 * members alive at each rebalance);
 * {@link com.quantfinlib.backtest.portfolio.PositionSizing} supplies Kelly,
 * fixed-fractional risk, inverse-volatility weights and vol-target leverage.
 */
package com.quantfinlib.backtest.portfolio;
