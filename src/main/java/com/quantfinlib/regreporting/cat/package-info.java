/**
 * US Consolidated Audit Trail (CAT) order-lifecycle events for industry members.
 *
 * <p>The plain idea: CAT asks a broker to report each step in an order's life (accepted,
 * routed, filled, traded) as a separate event, and to link the steps by a firm-assigned
 * order ID so the regulator can stitch the chain back together. This package builds those
 * events as {@link com.quantfinlib.regreporting.TransactionReport}s and writes them as JSON
 * lines.</p>
 *
 * <p><b>Not certified.</b> Field names follow the published CAT Reporting Technical
 * Specifications for Industry Members (event types MENO, MEOR, MEOT, MEOF), but only a core
 * subset is populated (listed on {@link com.quantfinlib.regreporting.cat.CatEventBuilder}), and
 * the output has not been validated against the CAT JSON schema or submitted to the CAT
 * test environment. Use it as a structured starting point, not as a filing.</p>
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
