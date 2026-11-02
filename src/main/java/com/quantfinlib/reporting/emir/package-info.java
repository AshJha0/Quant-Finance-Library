/**
 * EMIR Refit derivative trade-state reports (core fields).
 *
 * <p>The plain idea: under EMIR each counterparty to a derivative reports the trade to a
 * trade repository, and every later change (a correction, a valuation, an early
 * termination) is a new message pointing at the same trade through its Unique Transaction
 * Identifier (UTI). Refit adds two codes to every message: the action type (what the
 * message does to the record) and the event type (what happened in the business).</p>
 *
 * <p><b>Not certified.</b> The field set is a core subset of the Refit table of fields
 * (Commission Implementing Regulation (EU) 2022/1860, Annex, Tables 1 and 2), listed on
 * {@link com.quantfinlib.reporting.emir.EmirRefitReportBuilder}. The action/event pairing
 * check covers only the pairs listed on {@link com.quantfinlib.reporting.emir.EmirActionType}.
 * Output is a field map, not ISO 20022 auth.030 XML, and has not been validated against the
 * ESMA XSD or the ESMA validation rules.</p>
 */
@NullMarked
package com.quantfinlib.reporting.emir;

import org.jspecify.annotations.NullMarked;
