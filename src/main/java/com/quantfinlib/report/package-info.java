/**
 * Professional report generation, all writers hand-rolled on the JDK:
 * {@link com.quantfinlib.report.ReportGenerator} assembles portfolio,
 * performance, risk, allocation, trade, Monte Carlo and technical sections
 * into a {@link com.quantfinlib.report.Report}, exported as HTML (with
 * inline {@link com.quantfinlib.report.SvgCharts} equity/drawdown charts),
 * CSV, PDF, or XLSX. Implement
 * {@link com.quantfinlib.report.ReportExporter} for custom formats.
 */
package com.quantfinlib.report;
