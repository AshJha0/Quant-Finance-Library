/**
 * Stock screening by technical and fundamental filters, with ranking and
 * CSV export. {@link com.quantfinlib.screener.StockScreener} applies
 * composable {@link com.quantfinlib.screener.ScreenFilter}s
 * ({@link com.quantfinlib.screener.TechnicalFilters}: RSI, moving averages,
 * MACD, ADX, VWAP, SuperTrend, Bollinger, Ichimoku, breakouts, volume
 * spikes, gaps, 52-week levels;
 * {@link com.quantfinlib.screener.FundamentalFilters}: market cap, P/E, P/B,
 * EPS, ROE, dividend yield, leverage), then ranks survivors with the
 * weighted min-max {@link com.quantfinlib.screener.RankingEngine} and
 * exports to CSV.
 *
 * <p><b>4.0.</b> This package stays in the stable jar in 4.0. The 3.1 design
 * proposed moving it out, but stable packages ({@code alpha}) depend on it,
 * and a move would make the module graph cyclic. See docs/DESIGN_4.0.md.</p>
 */
@NullMarked
package com.quantfinlib.screener;

import org.jspecify.annotations.NullMarked;
