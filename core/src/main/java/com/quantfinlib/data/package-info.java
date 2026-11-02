/**
 * Loading, recording, replaying and aligning market data. The main
 * classes:
 * {@link com.quantfinlib.data.CsvBarLoader} (RFC-4180-tolerant CSV bars),
 * {@link com.quantfinlib.data.HttpBarFetcher} (CSV over HTTP),
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
 * with {@link com.quantfinlib.data.UniverseCsvLoader} as its documented CSV
 * interchange format for user-supplied membership/lifecycle data.
 *
 * <p>Since 3.0 the QFLT tick-file reader and writer live in
 * {@code com.quantfinlib.marketdata.tick}, and the live-bus recorders
 * ({@code TickCapture}, {@code AsyncTickCapture}) in {@code marketdata} in the
 * artifact {@code quant-finance-library-lowlatency}. See docs/MIGRATION_3.0.md.</p>
 *
 * <p><b>Moves in 4.0.</b> The package stays in the stable jar, but its three
 * I/O classes leave it: {@code CsvBarLoader}, {@code HttpBarFetcher} and
 * {@code UniverseCsvLoader} are deprecated for removal in 3.2.0 and delegate
 * to the same-named classes in {@code com.quantfinlib.io}, which moves to
 * {@code quant-finance-library-io} in 4.0. Change the import from {@code
 * data} to {@code io}. See docs/DESIGN_4.0.md and docs/MIGRATION_4.0.md.</p>
 */
@NullMarked
package com.quantfinlib.data;

import org.jspecify.annotations.NullMarked;
