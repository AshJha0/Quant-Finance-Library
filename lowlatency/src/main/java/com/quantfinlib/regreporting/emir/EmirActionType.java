package com.quantfinlib.regreporting.emir;

import java.util.EnumSet;
import java.util.Set;

/**
 * EMIR Refit action types (field 2.151). Each carries the event types (field 2.152) it
 * accepts, as published in ESMA74-362-2683, sheet Validations_Cpty_derivative, row 185,
 * EMIR-VR-2152-02 to -05: NEWT needs one of its set; MODI may carry one of its set or none;
 * TERM needs one of its set (column P is 'M'); CORR, EROR, REVI, VALU and POSC carry none.
 * Evidence: published reference.
 */
public enum EmirActionType {
    /** New. */
    NEWT(EnumSet.of(EmirEventType.TRAD, EmirEventType.NOVA, EmirEventType.COMP, EmirEventType.CLRG,
            EmirEventType.EXER, EmirEventType.ALOC, EmirEventType.CORP, EmirEventType.INCP)),
    /** Modify. */
    MODI(EnumSet.of(EmirEventType.TRAD, EmirEventType.NOVA, EmirEventType.COMP, EmirEventType.ETRM,
            EmirEventType.EXER, EmirEventType.ALOC, EmirEventType.CREV, EmirEventType.CORP, EmirEventType.INCP,
            EmirEventType.UPDT)),
    /** Correct. */
    CORR(EnumSet.noneOf(EmirEventType.class)),
    /** Terminate. */
    TERM(EnumSet.of(EmirEventType.NOVA, EmirEventType.COMP, EmirEventType.ETRM, EmirEventType.CLRG,
            EmirEventType.EXER, EmirEventType.ALOC, EmirEventType.CREV, EmirEventType.CORP, EmirEventType.INCP)),
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
            return allowed.isEmpty() || this == MODI;
        }
        return allowed.contains(e);
    }
}
