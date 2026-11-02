/**
 * EMIR Refit derivative trade-state reports (core fields).
 *
 * <p>The plain idea: under EMIR each counterparty to a derivative reports the trade to a
 * trade repository, and every later change (a correction, a valuation, an early
 * termination) is a new message pointing at the same trade through its Unique Transaction
 * Identifier (UTI). Refit adds two codes to every message: the action type (what the
 * message does to the record) and the event type (what happened in the business).</p>
 *
 * <p><b>Not certified.</b> The field set is a core subset of the Refit table of fields,
 * listed on {@link com.quantfinlib.regreporting.emir.EmirRefitReportBuilder}. Validation is
 * checked against the ESMA EMIR Refit validation rules, ESMA74-362-2683 (sheet
 * Validations_Cpty_derivative), for the single-report, data-only rules of the fields this
 * package populates; {@link com.quantfinlib.regreporting.emir.EmirRefitReport#rules()} lists
 * them with their rows and error codes. Field numbers and formats were checked against the
 * Annex of Implementing Regulation (EU) 2022/1860 (Tables 1 and 2). Output is
 * a field map, not ISO 20022 auth.030 XML, and has not been validated against the ESMA XSD.</p>
 *
 * <p>Not checked, because they need earlier reports for the UTI, the trade repository's
 * receipt time or reference data: EMIR-VR-1001-02/03 (receipt time), the GLEIF status parts
 * of EMIR-VR-1002/1003/1004/1009, EMIR-VR-1003-01 (needs the venue MIC of 2.41),
 * EMIR-VR-1008-02 and -1009-08/09 (unchanged since first report), EMIR-VR-2001-03/04,
 * EMIR-VR-2042-03, EMIR-VR-2045-03/06/07/08, EMIR-VR-2151-02 to -06 (action history),
 * EMIR-VR-2153-06, and membership of the official ISO 4217 list. Since 3.1.0 the opt-in
 * {@link com.quantfinlib.regreporting.emir.EmirStatefulValidator} applies EMIR-VR-1001-02/03,
 * the GLEIF status parts of 1002/1003/1004/1009, 1004-05, 1009-09, 2151-02 to -06, 2056-01 and
 * 2022-01, given a {@link com.quantfinlib.regreporting.emir.UtiHistory}, a receipt time, an
 * LEI registry and a {@link com.quantfinlib.regreporting.emir.CurrencyRegistry}.</p>
 *
 * <p><b>Added later in 3.1.0</b> (same opt-in validator): the GLEIF branch tests
 * EMIR-VR-1002-04, 1003-05, 1004-04 and 1009-04 (from
 * {@link com.quantfinlib.regreporting.registry.LeiRecord#entityCategory()}), EMIR-VR-1003-01
 * (given an ISO 10383 registry and the venue under the builder key {@code venueOfExecution}),
 * EMIR-VR-1008-02, EMIR-VR-2001-03 (given a caller trade key), EMIR-VR-2042-03,
 * EMIR-VR-2044-04, EMIR-VR-2045-02/03/06/07/08 and EMIR-VR-2153-06/08 in their "reported for
 * that UTI" form.</p>
 *
 * <p><b>Still not checked</b>, by either validator: EMIR-VR-1009-08 (client code prefix) and
 * EMIR-VR-2001-04 (2.1 against 2.2 to 2.4), whose fields this package does not populate;
 * third-country regulated markets in EMIR-VR-1003-01 (equivalence is not in ISO 10383).
 * Fields outside the populated subset (margins, collateral, leg 2, schedules, packages) are
 * not checked.</p>
 *
 * <p><b>3.0 notice.</b> Renamed to {@code com.quantfinlib.regreporting.emir} in 3.0
 * (the {@code report} and {@code reporting} names were one letter apart with
 * unrelated meanings), and moved with the rest of the reference tier to the
 * separate artifact {@code quant-finance-library-lowlatency}. Types and members
 * are unchanged; update the import. See docs/MIGRATION_3.0.md.</p>
 */
@NullMarked
package com.quantfinlib.regreporting.emir;

import org.jspecify.annotations.NullMarked;
