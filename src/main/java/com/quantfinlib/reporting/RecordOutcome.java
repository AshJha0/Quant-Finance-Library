package com.quantfinlib.reporting;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The result of validating one transaction record, in the shape of an auth.031
 * {@code RcrdSts} entry.
 *
 * @param recordId field 4 followed by field 2 with nothing between them, the record
 *        identifier of ESMA65-8-2356 paragraph 103
 * @param cancellation true for a cancellation report (field 1 {@code CANC})
 * @param status the record status
 * @param errors rule violations, each starting with its error code and a colon (for
 *        example {@code "CON-023: ..."}); empty for an accepted record
 * @since 3.0.0
 */
public record RecordOutcome(String recordId, boolean cancellation, RecordStatus status, List<String> errors) {
    /** Validates and copies. */
    public RecordOutcome {
        Objects.requireNonNull(recordId, "recordId");
        Objects.requireNonNull(status, "status");
        errors = List.copyOf(errors);
    }

    /**
     * The error codes alone (the text before the first colon of each error), in order.
     *
     * @return the codes
     */
    public List<String> codes() {
        List<String> out = new ArrayList<>(errors.size());
        for (String e : errors) {
            int c = e.indexOf(':');
            out.add(c < 0 ? e : e.substring(0, c));
        }
        return List.copyOf(out);
    }
}
