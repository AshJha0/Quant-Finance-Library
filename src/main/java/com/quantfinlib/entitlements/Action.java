package com.quantfinlib.entitlements;

/** The things a user can be entitled to do. */
public enum Action {
    /** Send a new order. */
    SUBMIT_ORDER,
    /** Amend (cancel/replace) a working order. */
    AMEND_ORDER,
    /** Cancel a working order. */
    CANCEL_ORDER,
    /** Read positions, orders and fills. */
    VIEW,
    /** Change limits or other risk configuration. */
    CHANGE_LIMITS,
    /** Approve a request raised by someone else (four-eyes). */
    APPROVE
}
