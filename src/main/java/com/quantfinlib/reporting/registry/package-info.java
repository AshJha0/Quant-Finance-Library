/**
 * Reference-data lookups used by transaction reports: LEI (GLEIF), ISIN admitted to
 * trading (ESMA FIRDS) and MIC (ISO 10383).
 *
 * <p>The plain idea: a report can be shaped correctly and still name an entity whose LEI
 * lapsed, an instrument that is not admitted anywhere, or a venue code that expired. The
 * check digits in {@link com.quantfinlib.reporting.ReportingIdentifiers} cannot catch that;
 * only the registries can. Every registry here has an offline implementation that loads a
 * file you downloaded yourself, so tests and batch jobs never need the network. One
 * optional online client ({@link com.quantfinlib.reporting.registry.GleifApiLeiRegistry})
 * calls the public GLEIF API with explicit timeouts.</p>
 *
 * <p>Parsers read the columns and elements named in each class's javadoc. They were tested
 * against small hand-made fixtures that follow the published layouts, not against a full
 * bulk file, so check a real download once before relying on them in production.</p>
 *
 * <p><b>3.0 notice.</b> Renamed to {@code com.quantfinlib.regreporting.registry} in 3.0
 * (the {@code report} and {@code reporting} names were one letter apart with
 * unrelated meanings), and moved with the rest of the reference tier to the
 * separate artifact {@code quant-finance-library-lowlatency}. Types and members
 * are unchanged; update the import. See docs/MIGRATION_3.0.md.</p>
 */
@NullMarked
package com.quantfinlib.reporting.registry;

import org.jspecify.annotations.NullMarked;
