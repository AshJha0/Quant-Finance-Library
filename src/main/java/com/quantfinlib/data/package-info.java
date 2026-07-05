/**
 * Data in, out, and preparation — the bridge between real-world files/feeds
 * and the analytics stack:
 * {@link com.quantfinlib.data.CsvBarLoader} (RFC-4180-tolerant CSV bars),
 * {@link com.quantfinlib.data.HttpBarFetcher} (CSV over HTTP),
 * {@link com.quantfinlib.data.TickFileWriter}/{@link com.quantfinlib.data.TickFileReader}
 * (QFLT binary tick format with as-fast-as-possible or paced replay),
 * {@link com.quantfinlib.data.TickCapture} (record the live bus for
 * deterministic replay), {@link com.quantfinlib.data.SeriesAligner}
 * (timestamp intersection / union+forward-fill for ragged multi-asset data)
 * {@link com.quantfinlib.data.CorporateActions} (split/dividend
 * back-adjustment) and {@link com.quantfinlib.data.PointInTimeUniverse}
 * (as-of membership + delisting/merger terminal events — the engine half of
 * survivorship-bias-free backtesting, consumed by the universe-aware
 * {@code PortfolioBacktester} overload and {@code StockScreener.membersAsOf})
 * with {@link com.quantfinlib.data.UniverseCsvLoader} as its documented CSV
 * interchange format for user-supplied membership/lifecycle data.
 */
package com.quantfinlib.data;
