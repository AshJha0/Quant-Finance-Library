/**
 * US Consolidated Audit Trail (CAT) order-lifecycle events for industry members.
 *
 * <p>The plain idea: CAT asks a broker to report each step in an order's life (accepted,
 * routed, filled, traded) as a separate event, and to link the steps by a firm-assigned
 * order ID so the regulator can stitch the chain back together. This package builds those
 * events as {@link com.quantfinlib.reporting.TransactionReport}s and writes them as JSON
 * lines.</p>
 *
 * <p><b>Not certified.</b> Field names and single-record checks are checked against the CAT
 * Reporting Technical Specifications for Industry Members v4.1.0 r14 (2026-02-13): Tables 3,
 * 16, 18, 48, 52 and 165, section 7.6 and Appendix G, for the equity event types MENO, MEOR,
 * MEOT and MEOF; {@link com.quantfinlib.reporting.cat.CatEvent#rules()} lists each rule.
 * Only a core subset of fields is populated (listed on
 * {@link com.quantfinlib.reporting.cat.CatEventBuilder}); linkage, reference data (symbol
 * master, IMID list, FDID), Name/Value pair contents, conditional fields other than price
 * and errorROEID, option and quote events, and the CAT JSON schema itself are not checked,
 * and nothing has been submitted to the CAT test environment. Use it as a structured
 * starting point, not as a filing.</p>
 *
 * <p><b>3.0 notice.</b> Renamed to {@code com.quantfinlib.regreporting.cat} in 3.0
 * (the {@code report} and {@code reporting} names were one letter apart with
 * unrelated meanings), and moved with the rest of the reference tier to the
 * separate artifact {@code quant-finance-library-lowlatency}. Types and members
 * are unchanged; update the import. See docs/MIGRATION_3.0.md.</p>
 */
@NullMarked
package com.quantfinlib.reporting.cat;

import org.jspecify.annotations.NullMarked;
