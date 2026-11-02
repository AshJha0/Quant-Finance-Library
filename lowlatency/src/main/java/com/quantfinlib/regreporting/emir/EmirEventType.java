package com.quantfinlib.regreporting.emir;

/**
 * EMIR Refit event types (field 2.152). The published list (ESMA74-362-2683, sheet
 * Validations_Cpty_derivative, row 185, EMIR-VR-2152-01) is TRAD, NOVA, COMP, ETRM, CLRG,
 * EXER, ALOC, CREV, CORP, INCP and UPDT; {@link #PTNG} is not in it.
 */
public enum EmirEventType {
    /** Trade. */
    TRAD,
    /** Step-in / novation. */
    NOVA,
    /** Post-trade risk reduction (compression). */
    COMP,
    /** Early termination. */
    ETRM,
    /** Clearing. */
    CLRG,
    /** Exercise. */
    EXER,
    /** Allocation. */
    ALOC,
    /** Credit event. */
    CREV,
    /** Inclusion in position. */
    INCP,
    /** Corporate event. */
    CORP,
    /** Update (outstanding trade brought to Refit). */
    UPDT,
    /**
     * Kept from 2.x for source compatibility only. Not in the ESMA list, so
     * {@link EmirRefitReport#validate()} rejects it (EMIR-VR-2152-01).
     */
    PTNG
}
