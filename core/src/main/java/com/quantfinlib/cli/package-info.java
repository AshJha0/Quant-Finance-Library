/**
 * Command-line interface for backtests, walk-forward runs and reports.
 * {@link com.quantfinlib.cli.Main} exposes
 * {@code backtest}, {@code walkforward} and {@code report} subcommands over
 * CSV bar files, so the library is usable without writing Java. The
 * published jar is runnable ({@code java -jar quant-finance-library-*.jar}).
 * Exit codes: 0 success, 1 usage error, 2 execution failure.
 *
 * <p><b>Moves in 4.0.</b> This package leaves the stable jar and ships, with
 * its name unchanged, in the {@code examples} classifier jar of the
 * reference-tier artifact {@code quant-finance-library-lowlatency} (module
 * {@code com.quantfinlib.lowlatency}). It is a demonstration front end, not
 * a stable API. Run it from the examples jar, or copy the code. See
 * docs/DESIGN_4.0.md and docs/MIGRATION_4.0.md.</p>
 */
@NullMarked
package com.quantfinlib.cli;

import org.jspecify.annotations.NullMarked;
