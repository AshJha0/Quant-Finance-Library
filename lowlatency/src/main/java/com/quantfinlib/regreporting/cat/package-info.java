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
 * Reporting Technical Specifications for Industry Members v4.2.0 r4 (2026-10-21): Tables 3,
 * 15, 17, 47, 51 and 148, section 7.6 and Appendix G, for the equity event types MENO, MEOR,
 * MEOT and MEOF; {@link com.quantfinlib.regreporting.cat.CatEvent#rules()} lists each rule.
 * Only a core subset of fields is populated (listed on
 * {@link com.quantfinlib.regreporting.cat.CatEventBuilder}); linkage, reference data (symbol
 * master, IMID list, FDID), Name/Value pair contents, conditional fields other than price
 * and errorROEID, and the CAT JSON schema itself are not checked,
 * and nothing has been submitted to the CAT test environment. Use it as a structured
 * starting point, not as a filing.</p>
 *
 * <p><b>Since 3.1.0.</b> Four more event types are built and checked: new quote (MENQ,
 * Table 38), new option order (MONO, Table 61), option order route (MOOR, Table 63) and new
 * option quote (MONQ, v4.1.0 r14 Table 85). {@link com.quantfinlib.regreporting.cat.CatLinkageChecker}
 * checks Order, Trade, Fulfillment and Quote Keys across a batch (section 2.6.1, Table 5).
 * Also since 3.1.0: the option lifecycle events MOOA, MOOM, MOOC, MOOT and MOOF
 * (Tables 69, 78, 82, 84, 86), the supplements MENOS, MONOS and MLOS (Tables 16, 62, 122),
 * the multi-leg events MLNO, MLOR and MLOC with their legDetails (Tables 94 to 97, 120),
 * linkage across several files ({@code CatLinkageChecker.checkFiles}), and a
 * dependency-free check of JSON record lines against a CAT schema file in the section
 * 6.1.4.2 layout ({@link com.quantfinlib.regreporting.cat.CatSchema}; the bundled schema is
 * the library's transcription, not the official file). Since 3.2.0 the other 47 option and
 * multi-leg events of Tables 59 and 60 are built ({@code CatEventBuilder.start}) and checked
 * record by record, grouped by {@link com.quantfinlib.regreporting.cat.CatEventFamily}; their
 * linkage is not checked yet. Still not covered: equity events other than MENO, MENOS, MEOR,
 * MENQ, MEOT and MEOF, Name/Value Pair and side-detail contents, CAIS, and reference data.</p>
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
