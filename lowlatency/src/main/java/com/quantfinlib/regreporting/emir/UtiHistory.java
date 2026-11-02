package com.quantfinlib.regreporting.emir;

import java.util.List;

/**
 * The reports a trade repository has already accepted for one counterparty and one UTI, in
 * the order they were accepted. {@link EmirStatefulValidator} reads it to apply the action
 * type history rules EMIR-VR-2151-02 to -06 and the "unchanged since the first report" rules
 * EMIR-VR-1004-05 and EMIR-VR-1009-09 (ESMA74-362-2683, sheet Validations_Cpty_derivative,
 * rows 8, 13 and 184).
 *
 * <p>The plain idea: a single EMIR report cannot tell whether it is the first one for its
 * trade; the repository can, because it keeps every earlier report. This interface is that
 * memory. The library ships {@link InMemoryUtiHistory}; a production system would back it
 * with the repository's own store or the firm's reconciliation database.</p>
 *
 * @since 3.1.0
 */
public interface UtiHistory {
    /**
     * Accepted reports for the pair, oldest first.
     *
     * @param counterpartyId field 1.4 (reporting counterparty LEI)
     * @param uti field 2.1
     * @return the reports, empty when none has been accepted
     */
    List<EmirRefitReport> accepted(String counterpartyId, String uti);
}
