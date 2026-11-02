/**
 * Command-line interface for backtests, walk-forward runs and reports.
 * {@link com.quantfinlib.cli.Main} exposes
 * {@code backtest}, {@code walkforward} and {@code report} subcommands over
 * CSV bar files, so the library is usable without writing Java. The
 * published jar is runnable ({@code java -jar quant-finance-library-*.jar}).
 * Exit codes: 0 success, 1 usage error, 2 execution failure.
 *
 * <p><b>Since 4.0.</b> The command line is not API: it ships in the {@code
 * examples} classifier jar of {@code quant-finance-library-lowlatency}
 * (module {@code com.quantfinlib.lowlatency}) (main class {@code
 * com.quantfinlib.cli.Main}). See docs/MIGRATION_4.0.md.</p>
 */
@NullMarked
package com.quantfinlib.cli;

import org.jspecify.annotations.NullMarked;
