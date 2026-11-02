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
 * blank for the firm. {@code Rts22TransactionReport.toAuth016Xml} writes the ISO
 * 20022 auth.016.001.01 {@code Document} of the ESMA usage guideline
 * auth.016.001.01_ESMAUG_Reporting_1.1.0, with fields placed as in section 6 of
 * the ESMA technical reporting instructions (ESMA65-8-2356). Output passes the
 * ESMA XSD in an own test (run with {@code QFL_ESMA_XSD} pointing at the XSD; the
 * schema is SWIFT MyStandards licensed and is not shipped). The business
 * application header and envelope are not written. CSV and a simple XML
 * rendering remain for inspection.
 *
 * <p>The flat 65-field layout loses some type information the message needs.
 * The writer recovers it by these rules, which callers must respect:
 * <ul>
 *   <li>Fields 7, 16 (buyer, seller): a valid 20-character LEI is written as
 *       {@code LEI}, {@code INTC} as {@code Intl}, any other 4-character code as
 *       {@code MIC}; anything else is a natural person and needs fields 9 to 11
 *       (or 18 to 20). Fields 12 and 21 likewise: LEI, else a person with
 *       fields 13 to 15 (22 to 24).</li>
 *   <li>National identifiers: a value shaped like a CONCAT code (2 letters, 8
 *       digits, 10 letters or #) is written with scheme {@code CONCAT}; a value
 *       prefixed {@code CCPT:} with scheme CCPT; anything else, or a value
 *       prefixed {@code NIDN:}, with scheme NIDN.</li>
 *   <li>Fields 57 and 59: a person when the matching branch-country field (58
 *       or 60) is populated, otherwise an algorithm code; field 59 {@code NORE}
 *       is written as {@code Clnt}.</li>
 *   <li>Field 30 is in units unless field 31 is set, then a nominal value in
 *       that currency. Fields 33 and 51 are monetary prices in the currency of
 *       34 or 52 (negative values get {@code Sgn false}), or {@code PNDG} /
 *       {@code NOAP} for no price. Percentage, yield and basis-point prices are
 *       not supported.</li>
 *   <li>Field 41 alone gives {@code FinInstrm/Id}. When field 42 or 43 is set
 *       the full description ({@code FinInstrm/Othr}) is written instead, with
 *       field 41 as the optional ISIN inside it. Field 47 may hold several ISINs
 *       separated by spaces (a basket). Field 48 is a benchmark code when it is
 *       one of the ISO 20022 BenchmarkCurveName2Code values, else a free name.
 *       Field 49 is written as {@code <n><DAYS|WEEK|MNTH|YEAR>}, e.g.
 *       {@code 3MNTH}. Field 45 goes under {@code Intrst} for CFI codes
 *       starting SR, JR or FFN, else under {@code FX}. Swap legs
 *       ({@code SwpIn}/{@code SwpOut}) are not supported.</li>
 *   <li>Fields 61 and 63 may hold several codes separated by spaces or
 *       commas. Field 64 maps to {@code RskRdcgTx}.</li>
 * </ul>
 *
 * <p>{@link com.quantfinlib.reporting.ReportingIdentifiers} checks identifier
 * syntax and check digits: LEI (ISO 17442, ISO 7064 MOD 97-10), ISIN (ISO 6166,
 * Luhn) and MIC (ISO 10383 shape only; the registry itself is not bundled).
 *
 * <p><b>3.0 notice.</b> Renamed to {@code com.quantfinlib.regreporting} in 3.0
 * (the {@code report} and {@code reporting} names were one letter apart with
 * unrelated meanings), and moved with the rest of the reference tier to the
 * separate artifact {@code quant-finance-library-lowlatency}. Types and members
 * are unchanged; update the import. See docs/MIGRATION_3.0.md.</p>
 */
@NullMarked
package com.quantfinlib.reporting;

import org.jspecify.annotations.NullMarked;
