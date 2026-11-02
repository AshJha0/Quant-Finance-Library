package com.quantfinlib.reporting;

import com.quantfinlib.reporting.registry.ReferenceData;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The state a competent authority keeps across transaction reports: the field 2 history
 * per executing entity and the queue of reports pending instrument reference data. A firm
 * runs it to see, before the authority does, how a file will be answered.
 *
 * <p>Plain idea: a transaction reference number may be used again only after the report
 * that used it was cancelled, a cancellation must point at a live report, and a report
 * whose only fault is an instrument missing from FIRDS waits up to seven days for the
 * instrument to appear instead of being rejected at once.
 *
 * <p>Formal statement, with sources (ESMA65-8-2594 Annex 1 v1.7 and ESMA65-8-2356, both
 * published reference):
 * <ul>
 *   <li>The record key is field 4 with field 2 (TRI paragraph 70).</li>
 *   <li>CON-023 (rule 005): a new report is rejected when the last accepted report with the
 *       same key was not a cancellation. A pending report counts as not cancelled (the
 *       library's reading: paragraph 71 lets a pending report be cancelled, so it is live).</li>
 *   <li>CON-024 (rule 006): a cancellation is rejected when no report with the key was
 *       accepted or is pending. CON-025 (rule 007): it is rejected when the last one was
 *       already cancelled. A rejected report leaves no trace (scenario 2 of paragraph 79).</li>
 *   <li>Cancelling a pending report aborts its validation and the cancellation is accepted
 *       (paragraph 72, scenario 3 of paragraph 79).</li>
 *   <li>A new report whose only errors are CON-411 or CON-471 is PDNG (paragraph 20(c));
 *       any other error rejects it at once with every error listed (paragraph 20(e)); a
 *       failed ISIN check digit, CON-410 or CON-470, is such an error (paragraph 20(d)).</li>
 *   <li>{@link #revalidate} repeats the reference-data rules for each pending report. It is
 *       accepted when they pass and rejected on any other error (paragraph 20(c)(iii) and
 *       (iv)). While no more than 7 calendar days have passed since reception it stays
 *       pending; after that it is rejected with its CON-411 or CON-471 errors (the CON-411
 *       error text, "it is the 7th day or before", and paragraph 20(c)(v)).</li>
 *   <li>A pending report is reported once, when it first pends, and again only when its
 *       status changes (paragraph 28), so {@link #revalidate} returns status changes only.</li>
 * </ul>
 *
 * <p>Worked example (scenario 3 of paragraph 79; {@code Rts22StatusTrackerTest}): TXN162 is
 * received on day T+1 for an ISIN not yet in FIRDS and is PDNG. On T+2 the cancellation for
 * TXN162 is ACPT and the queue is empty; a new report TXN162 is then accepted, since the last
 * accepted report for the key is a cancellation.
 *
 * <p>The data-only rules ({@link Rts22TransactionReport#validateEsmaRules}) and the
 * reference-data rules ({@link Rts22TransactionReport#validateWithReferenceData}, with the
 * reception date as the submission date) are the rules run. Not thread-safe; confine an
 * instance to one thread or lock around it.
 *
 * @since 3.0.0
 */
public final class Rts22StatusTracker {
    /** CON-411 error text: pending while no more than this many days have passed since reception. */
    public static final int PENDING_DAYS = 7;

    private static final Set<String> PENDING_CODES = Set.of("CON-411", "CON-471");

    /** State of the last accepted (or pending) report for a key. */
    public enum KeyState {
        /** A new report was accepted and not cancelled. */
        ACTIVE,
        /** A new report is pending instrument validation. */
        PENDING,
        /** The last accepted report was a cancellation. */
        CANCELLED
    }

    private record Pending(Rts22TransactionReport report, LocalDate received) {
    }

    private final Map<String, KeyState> history = new LinkedHashMap<>();
    private final Map<String, Pending> queue = new LinkedHashMap<>();

    /** An empty tracker. */
    public Rts22StatusTracker() {
    }

    /**
     * The record key: field 4 followed by field 2 (TRI paragraphs 70 and 103).
     *
     * @param report the report
     * @return the key
     */
    public static String recordId(Rts22TransactionReport report) {
        return report.get(Rts22Field.EXECUTING_ENTITY_ID) + report.get(Rts22Field.TRANSACTION_REFERENCE_NUMBER);
    }

    /**
     * Validates one report received on a date and updates the history.
     *
     * @param report the report
     * @param received the date the authority receives it (also the submission date for
     *        CON-040 and CON-060)
     * @param refs reference data as of that date
     * @return the outcome: ACPT, PDNG or RJCT
     */
    public RecordOutcome submit(Rts22TransactionReport report, LocalDate received, ReferenceData refs) {
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(received, "received");
        Objects.requireNonNull(refs, "refs");
        String key = recordId(report);
        boolean canc = "CANC".equals(report.get(Rts22Field.REPORT_STATUS));
        List<String> errs = new ArrayList<>(report.validateEsmaRules());
        errs.addAll(report.validateWithReferenceData(refs, received));
        KeyState state = history.get(key);
        if (canc) {
            if (state == null) {
                errs.add("CON-024: no transaction with record id " + key + " to cancel");
            } else if (state == KeyState.CANCELLED) {
                errs.add("CON-025: transaction " + key + " has already been cancelled");
            }
            if (!errs.isEmpty()) {
                return new RecordOutcome(key, true, RecordStatus.REJECTED, errs);
            }
            queue.remove(key);
            history.put(key, KeyState.CANCELLED);
            return new RecordOutcome(key, true, RecordStatus.ACCEPTED, List.of());
        }
        if (state == KeyState.ACTIVE || state == KeyState.PENDING) {
            errs.add("CON-023: transaction reference " + key + " has already been sent and not cancelled");
        }
        if (errs.isEmpty()) {
            history.put(key, KeyState.ACTIVE);
            return new RecordOutcome(key, false, RecordStatus.ACCEPTED, List.of());
        }
        if (onlyPending(errs)) {
            history.put(key, KeyState.PENDING);
            queue.put(key, new Pending(report, received));
            return new RecordOutcome(key, false, RecordStatus.PENDING, errs);
        }
        return new RecordOutcome(key, false, RecordStatus.REJECTED, errs);
    }

    /**
     * Repeats instrument validation for every pending report on a later day.
     *
     * @param day the validation day
     * @param refs reference data as of that day
     * @return the reports whose status changed (ACPT or RJCT), in reception order
     */
    public List<RecordOutcome> revalidate(LocalDate day, ReferenceData refs) {
        Objects.requireNonNull(day, "day");
        Objects.requireNonNull(refs, "refs");
        List<RecordOutcome> out = new ArrayList<>();
        Iterator<Map.Entry<String, Pending>> it = queue.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Pending> e = it.next();
            Pending p = e.getValue();
            List<String> errs = p.report().validateWithReferenceData(refs, p.received());
            String key = e.getKey();
            if (errs.isEmpty()) {
                history.put(key, KeyState.ACTIVE);
                out.add(new RecordOutcome(key, false, RecordStatus.ACCEPTED, List.of()));
                it.remove();
            } else if (!onlyPending(errs) || day.isAfter(p.received().plusDays(PENDING_DAYS))) {
                history.remove(key);
                out.add(new RecordOutcome(key, false, RecordStatus.REJECTED, errs));
                it.remove();
            }
        }
        return List.copyOf(out);
    }

    /**
     * The state of a key, or null when no report with it was accepted or is pending.
     *
     * @param recordId field 4 followed by field 2
     * @return the state, or null
     */
    public KeyState state(String recordId) {
        return history.get(Objects.requireNonNull(recordId, "recordId"));
    }

    /**
     * Seeds the history from an earlier run (for example a database of accepted reports).
     * A {@link KeyState#PENDING} state cannot be seeded, since the report itself is needed.
     *
     * @param recordId field 4 followed by field 2
     * @param state {@link KeyState#ACTIVE} or {@link KeyState#CANCELLED}
     * @throws IllegalArgumentException for {@link KeyState#PENDING}
     */
    public void restore(String recordId, KeyState state) {
        Objects.requireNonNull(recordId, "recordId");
        if (Objects.requireNonNull(state, "state") == KeyState.PENDING) {
            throw new IllegalArgumentException("a pending report must be submitted, not restored");
        }
        queue.remove(recordId);
        history.put(recordId, state);
    }

    /**
     * Record ids pending instrument validation, in reception order.
     *
     * @return the ids
     */
    public List<String> pending() {
        return List.copyOf(queue.keySet());
    }

    /** True when every error is CON-411 or CON-471; callers pass a non-empty list. */
    private static boolean onlyPending(List<String> errs) {
        return PENDING_CODES.containsAll(new RecordOutcome("", false, RecordStatus.PENDING, errs).codes());
    }
}
