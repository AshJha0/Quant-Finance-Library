/**
 * File and network I/O for bar and universe data, new in 3.2.0:
 * {@link com.quantfinlib.io.CsvBarLoader} (RFC-4180-tolerant CSV bars),
 * {@link com.quantfinlib.io.HttpBarFetcher} (CSV over HTTP, the one user of
 * {@code java.net.http}) and {@link com.quantfinlib.io.UniverseCsvLoader}
 * (the CSV interchange format for {@code data.PointInTimeUniverse}).
 *
 * <p><b>Since 4.0.</b> This package is in the incubating artifact {@code
 * quant-finance-library-io} (module {@code com.quantfinlib.io}), with {@code
 * com.quantfinlib.report}; it was added to the stable jar in 3.2.0 as the
 * replacement for the same-named {@code data} classes, which 4.0 removed.
 * See docs/MIGRATION_4.0.md.</p>
 */
@NullMarked
package com.quantfinlib.io;

import org.jspecify.annotations.NullMarked;
