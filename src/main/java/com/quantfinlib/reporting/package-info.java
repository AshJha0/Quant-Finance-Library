/**
 * Regulatory transaction reporting. {@link com.quantfinlib.reporting.TransactionReport}
 * is the regime-neutral view (a named regime plus ordered, validated fields) that a
 * CAT, EMIR or MiFIR adapter can share; {@link com.quantfinlib.reporting.TransactionReportSink}
 * is where finished reports go.
 *
 * <p>{@link com.quantfinlib.reporting.Rts22ReportBuilder} builds a MiFIR Article 26
 * transaction report laid out as the 65 fields of Annex I, Table 2 of Commission
 * Delegated Regulation (EU) 2017/590 (RTS 22). It fills the fields the library can
 * derive from an execution report plus firm reference data, and leaves the rest
 * blank for the firm. Output is CSV or a simple XML rendering for inspection and
 * reconciliation; it is NOT the ISO 20022 auth.016 message and has not been
 * validated against the ESMA XSD.
 *
 * <p>{@link com.quantfinlib.reporting.ReportingIdentifiers} checks identifier
 * syntax and check digits: LEI (ISO 17442, ISO 7064 MOD 97-10), ISIN (ISO 6166,
 * Luhn) and MIC (ISO 10383 shape only; the registry itself is not bundled).
 */
@NullMarked
package com.quantfinlib.reporting;

import org.jspecify.annotations.NullMarked;
