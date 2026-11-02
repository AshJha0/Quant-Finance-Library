package com.quantfinlib.reporting.registry;

import java.util.Locale;

/**
 * GLEIF registration status of an LEI record (the {@code Registration.RegistrationStatus}
 * field of the golden copy and {@code registration.status} of the API). A {@link #LAPSED}
 * LEI is still a valid identifier, but its reference data was not renewed.
 */
public enum LeiStatus {
    /** Pending validation by the issuing LOU. */
    PENDING_VALIDATION,
    /** Validated and maintained. */
    ISSUED,
    /** Duplicate of another LEI. */
    DUPLICATE,
    /** Not renewed by the next renewal date. */
    LAPSED,
    /** Entity merged into another. */
    MERGED,
    /** Retired by the LOU. */
    RETIRED,
    /** Issued in error. */
    ANNULLED,
    /** Cancelled before issuance. */
    CANCELLED,
    /** Transferred to another LOU. */
    TRANSFERRED,
    /** Transfer pending. */
    PENDING_TRANSFER,
    /** Archival pending. */
    PENDING_ARCHIVAL,
    /** Not found, or a value this library does not know. */
    UNKNOWN;

    /**
     * Parses a GLEIF status string, case-insensitively.
     *
     * @param s status text, e.g. {@code "ISSUED"}
     * @return the status, or {@link #UNKNOWN}
     */
    public static LeiStatus parse(String s) {
        try {
            return valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }

    /**
     * Whether the record is currently maintained (ISSUED, PENDING_TRANSFER, PENDING_ARCHIVAL).
     *
     * @return true if maintained
     */
    public boolean isMaintained() {
        return this == ISSUED || this == PENDING_TRANSFER || this == PENDING_ARCHIVAL;
    }
}
