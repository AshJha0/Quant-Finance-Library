/**
 * Report generation in HTML, PDF, XLSX and CSV, written with the JDK only.
 * {@link com.quantfinlib.report.ReportGenerator} assembles portfolio,
 * performance, risk, allocation, trade, Monte Carlo and technical sections
 * into a {@link com.quantfinlib.report.Report}, exported as HTML (with
 * inline {@link com.quantfinlib.report.SvgCharts} equity/drawdown charts),
 * CSV, PDF, or XLSX. Implement
 * {@link com.quantfinlib.report.ReportExporter} for custom formats.
 *
 * <p><b>Moves in 4.0.</b> This package moves, with its name unchanged, to
 * the incubating artifact {@code quant-finance-library-io} (module {@code
 * com.quantfinlib.io}). Add that dependency and {@code requires
 * com.quantfinlib.io;}; imports do not change. See docs/DESIGN_4.0.md and
 * docs/MIGRATION_4.0.md.</p>
 */
@NullMarked
package com.quantfinlib.report;

import org.jspecify.annotations.NullMarked;
