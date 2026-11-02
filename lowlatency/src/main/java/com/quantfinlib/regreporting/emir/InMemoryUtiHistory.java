package com.quantfinlib.regreporting.emir;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A {@link UtiHistory} held in memory, keyed by (field 1.4, field 2.1). Not thread-safe;
 * guard it externally when several threads record reports.
 *
 * <p>Worked example (own test): record a NEWT for UTI {@code U1}; a second NEWT for the same
 * counterparty and UTI then fails EMIR-VR-2151-03, while a NEWT from the other counterparty
 * for the same UTI passes, because the rule is per Counterparty ID-UTI pair.</p>
 *
 * @since 3.1.0
 */
public final class InMemoryUtiHistory implements UtiHistory {
    private final Map<String, List<EmirRefitReport>> byKey = new HashMap<>();

    /** Creates an empty history. */
    public InMemoryUtiHistory() {
        // empty
    }

    /**
     * Records a report the repository accepted. Call it only after the report has passed
     * validation; a rejected report is not part of the history (ESMA74-362-2683 rule
     * EMIR-VR-2151-04 speaks of 'REVI' being accepted).
     *
     * @param report the accepted report
     */
    public void accept(EmirRefitReport report) {
        Objects.requireNonNull(report, "report");
        byKey.computeIfAbsent(key(cpty(report), report.reference()), k -> new ArrayList<>()).add(report);
    }

    @Override
    public List<EmirRefitReport> accepted(String counterpartyId, String uti) {
        return List.copyOf(byKey.getOrDefault(key(counterpartyId, uti), List.of()));
    }

    private static String cpty(EmirRefitReport r) {
        return r.fields().getOrDefault("reportingCounterpartyId", "");
    }

    private static String key(String cpty, String uti) {
        return cpty + '\u0000' + uti;
    }
}
