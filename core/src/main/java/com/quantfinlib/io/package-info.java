/**
 * File and network I/O for bar and universe data, new in 3.2.0:
 * {@link com.quantfinlib.io.CsvBarLoader} (RFC-4180-tolerant CSV bars),
 * {@link com.quantfinlib.io.HttpBarFetcher} (CSV over HTTP, the one user of
 * {@code java.net.http}) and {@link com.quantfinlib.io.UniverseCsvLoader}
 * (the CSV interchange format for {@code data.PointInTimeUniverse}).
 *
 * <p><b>Moves in 4.0.</b> This package moves, with its name unchanged, to the
 * incubating artifact {@code quant-finance-library-io} (JPMS module
 * {@code com.quantfinlib.io}), together with {@code com.quantfinlib.report}.
 * Core then no longer requires {@code java.net.http}. A user adds that one
 * dependency and {@code requires com.quantfinlib.io;}; imports do not change.
 * The same-named classes in {@code com.quantfinlib.data} are deprecated for
 * removal in 3.2.0 and delegate here. See docs/DESIGN_4.0.md and
 * docs/MIGRATION_4.0.md.</p>
 */
@NullMarked
package com.quantfinlib.io;

import org.jspecify.annotations.NullMarked;
