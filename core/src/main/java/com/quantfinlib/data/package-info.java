/**
 * Loading, recording, replaying and aligning market data. The main
 * classes:
 * {@link com.quantfinlib.data.SeriesAligner}
 * (timestamp intersection / union+forward-fill for ragged multi-asset data)
 * {@link com.quantfinlib.data.CorporateActions} (split/dividend
 * back-adjustment), {@link com.quantfinlib.data.CorporateEvents} and
 * {@link com.quantfinlib.data.CorporateEventPositions} (bonus issues,
 * reverse splits, special dividends, spin-offs, rights, symbol changes,
 * cash-and-stock mergers) and {@link com.quantfinlib.data.PointInTimeUniverse}
 * (as-of membership + delisting/merger terminal events — the engine half of
 * survivorship-bias-free backtesting, consumed by the universe-aware
 * {@code PortfolioBacktester} overload and {@code StockScreener.membersAsOf})
 * with {@code io.UniverseCsvLoader} (module {@code com.quantfinlib.io}) as its documented CSV
 * interchange format for user-supplied membership/lifecycle data.
 *
 * <p>Since 3.0 the QFLT tick-file reader and writer live in
 * {@code com.quantfinlib.marketdata.tick}, and the live-bus recorders
 * ({@code TickCapture}, {@code AsyncTickCapture}) in {@code marketdata} in the
 * artifact {@code quant-finance-library-lowlatency}. See docs/MIGRATION_3.0.md.</p>
 *
 * <p><b>Since 4.0.</b> The I/O classes that were here ({@code CsvBarLoader},
 * {@code HttpBarFetcher}, {@code UniverseCsvLoader}) are in {@code
 * com.quantfinlib.io} in the {@code quant-finance-library-io} artifact; the
 * rest of the package is unchanged. See docs/MIGRATION_4.0.md.</p>
 */
@NullMarked
package com.quantfinlib.data;

import org.jspecify.annotations.NullMarked;
