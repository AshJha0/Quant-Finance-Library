package com.quantfinlib.reporting.emir;

/** EMIR Refit event types (Refit field "event type"). */
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
    /** Portfolio compression (partial termination). */
    PTNG
}
