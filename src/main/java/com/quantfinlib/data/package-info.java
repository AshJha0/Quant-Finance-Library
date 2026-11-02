/**
 * Loading, recording, replaying and aligning market data. The main
 * classes:
 * {@link com.quantfinlib.data.CsvBarLoader} (RFC-4180-tolerant CSV bars),
 * {@link com.quantfinlib.data.HttpBarFetcher} (CSV over HTTP),
 * {@link com.quantfinlib.data.TickFileWriter}/{@link com.quantfinlib.data.TickFileReader}
 * (QFLT binary tick format with as-fast-as-possible or paced replay),
 * {@link com.quantfinlib.data.TickCapture} (record the live bus for
 * deterministic replay), {@link com.quantfinlib.data.SeriesAligner}
 * (timestamp intersection / union+forward-fill for ragged multi-asset data)
 * {@link com.quantfinlib.data.CorporateActions} (split/dividend
 * back-adjustment), {@link com.quantfinlib.data.CorporateEvents} and
 * {@link com.quantfinlib.data.CorporateEventPositions} (bonus issues,
 * reverse splits, special dividends, spin-offs, rights, symbol changes,
 * cash-and-stock mergers) and {@link com.quantfinlib.data.PointInTimeUniverse}
 * (as-of membership + delisting/merger terminal events — the engine half of
 * survivorship-bias-free backtesting, consumed by the universe-aware
 * {@code PortfolioBacktester} overload and {@code StockScreener.membersAsOf})
 * with {@link com.quantfinlib.data.UniverseCsvLoader} as its documented CSV
 * interchange format for user-supplied membership/lifecycle data.
 */
@NullMarked
package com.quantfinlib.data;

import org.jspecify.annotations.NullMarked;
