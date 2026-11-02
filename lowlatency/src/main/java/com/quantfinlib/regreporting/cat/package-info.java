/**
 * US Consolidated Audit Trail (CAT) order-lifecycle events for industry members.
 *
 * <p>The plain idea: CAT asks a broker to report each step in an order's life (accepted,
 * routed, filled, traded) as a separate event, and to link the steps by a firm-assigned
 * order ID so the regulator can stitch the chain back together. This package builds those
 * events as {@link com.quantfinlib.regreporting.TransactionReport}s and writes them as JSON
 * lines.</p>
 *
 * <p><b>Not certified.</b> Field names and single-record checks are checked against the CAT
 * Reporting Technical Specifications for Industry Members v4.1.0 r14 (2026-02-13): Tables 3,
 * 16, 18, 48, 52 and 165, section 7.6 and Appendix G, for the equity event types MENO, MEOR,
 * MEOT and MEOF; {@link com.quantfinlib.regreporting.cat.CatEvent#rules()} lists each rule.
 * Only a core subset of fields is populated (listed on
 * {@link com.quantfinlib.regreporting.cat.CatEventBuilder}); linkage, reference data (symbol
 * master, IMID list, FDID), Name/Value pair contents, conditional fields other than price
 * and errorROEID, and the CAT JSON schema itself are not checked,
 * and nothing has been submitted to the CAT test environment. Use it as a structured
 * starting point, not as a filing.</p>
 *
 * <p><b>Since 3.1.0.</b> Four more event types are built and checked: new quote (MENQ,
 * Table 39), new option order (MONO, Table 62), option order route (MOOR, Table 64) and new
 * option quote (MONQ, Table 85). {@link com.quantfinlib.regreporting.cat.CatLinkageChecker}
 * checks Order, Trade, Fulfillment and Quote Keys across a batch (section 2.6.1, Table 5).
 * Also since 3.1.0: the option lifecycle events MOOA, MOOM, MOOC, MOOT and MOOF
 * (Tables 70, 79, 83, 90, 92), the supplements MENOS, MONOS and MLOS (Tables 17, 63, 139),
 * the multi-leg events MLNO, MLOR and MLOC with their legDetails (Tables 100 to 103, 126),
 * linkage across several files ({@code CatLinkageChecker.checkFiles}), and a
 * dependency-free check of JSON record lines against a CAT schema file in the section
 * 6.1.5.2 layout ({@link com.quantfinlib.regreporting.cat.CatSchema}; the bundled schema is
 * the library's transcription, not the official file). Still not covered: the other option
 * and multi-leg events of Tables 60 and 61 (internal routes, child orders, quote lifecycle,
 * allocations, effective events, modification and cancel requests), Name/Value Pair and
 * side-detail contents, CAIS, and reference data.</p>
 *
 * <p><b>3.0 notice.</b> Renamed to {@code com.quantfinlib.regreporting.cat} in 3.0
 * (the {@code report} and {@code reporting} names were one letter apart with
 * unrelated meanings), and moved with the rest of the reference tier to the
 * separate artifact {@code quant-finance-library-lowlatency}. Types and members
 * are unchanged; update the import. See docs/MIGRATION_3.0.md.</p>
 */
@NullMarked
package com.quantfinlib.regreporting.cat;

import org.jspecify.annotations.NullMarked;
