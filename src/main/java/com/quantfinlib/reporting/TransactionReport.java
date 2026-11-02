package com.quantfinlib.reporting;

import java.util.List;
import java.util.Map;

/**
 * A regime-neutral transaction report: an ordered map of field name to value
 * and a validation pass. MiFIR RTS 22 ({@link Rts22TransactionReport}) is the one
 * regime implemented here; CAT (SEC Rule 613) or EMIR adapters can implement the
 * same interface so storage, reconciliation and submission code need not care
 * which regime a report belongs to.
 */
public interface TransactionReport {
    /** Regime label, for example {@code "MiFIR-RTS22"}. */
    String regime();

    /** Unique reference for this report within the submitting firm. */
    String reference();

    /** Field name to value, in the regime's field order; blank means not populated. */
    Map<String, String> fields();

    /** Problems found; empty when the report passes this library's checks. */
    List<String> validate();
}
