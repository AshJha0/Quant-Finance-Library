package com.quantfinlib.reporting.emir;

import java.util.EnumSet;
import java.util.Set;

/**
 * EMIR Refit action types (Refit field "action type"; number indicative, not re-checked). Each carries the event types this library accepts
 * with it. The pairing is a conservative reading of the ESMA Refit guidelines and is not
 * the full ESMA validation table: NEWT and MODI take business events, CORR takes any event,
 * VALU and POSC take none, and TERM, EROR and REVI take none except TERM with ETRM.
 */
public enum EmirActionType {
    /** New. */
    NEWT(EnumSet.of(EmirEventType.TRAD, EmirEventType.NOVA, EmirEventType.COMP, EmirEventType.CLRG,
            EmirEventType.EXER, EmirEventType.ALOC, EmirEventType.CREV, EmirEventType.PTNG, EmirEventType.INCP)),
    /** Modify. */
    MODI(EnumSet.of(EmirEventType.TRAD, EmirEventType.NOVA, EmirEventType.COMP, EmirEventType.EXER,
            EmirEventType.ALOC, EmirEventType.CREV, EmirEventType.PTNG, EmirEventType.INCP, EmirEventType.UPDT)),
    /** Correct. */
    CORR(EnumSet.allOf(EmirEventType.class)),
    /** Terminate. */
    TERM(EnumSet.of(EmirEventType.ETRM)),
    /** Error (report should never have been sent). */
    EROR(EnumSet.noneOf(EmirEventType.class)),
    /** Revive a terminated or errored trade. */
    REVI(EnumSet.noneOf(EmirEventType.class)),
    /** Valuation update. */
    VALU(EnumSet.noneOf(EmirEventType.class)),
    /** Position component. */
    POSC(EnumSet.noneOf(EmirEventType.class));

    private final Set<EmirEventType> allowed;

    EmirActionType(Set<EmirEventType> allowed) {
        this.allowed = allowed;
    }

    /**
     * Whether an event type may accompany this action ({@code null} means no event type).
     *
     * @param e the event type, or null
     * @return true if accepted
     */
    public boolean accepts(EmirEventType e) {
        if (e == null) {
            return allowed.isEmpty() || this == CORR;
        }
        return allowed.contains(e);
    }
}
