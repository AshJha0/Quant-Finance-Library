package com.quantfinlib.regreporting.registry;

import java.util.Locale;

/** ISO 10383 MIC status (the {@code STATUS} column of the ISO 10383 CSV). */
public enum MicStatus {
    /** In use. */
    ACTIVE,
    /** Changed in the latest release, still in use. */
    UPDATED,
    /** No longer valid. */
    EXPIRED,
    /** Not in the file, or an unrecognised value. */
    UNKNOWN;

    /**
     * Parses a status, case-insensitively.
     *
     * @param s the text
     * @return the status, or {@link #UNKNOWN}
     */
    public static MicStatus parse(String s) {
        try {
            return valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }

    /**
     * Whether a report may use the code (ACTIVE or UPDATED).
     *
     * @return true if usable
     */
    public boolean isUsable() {
        return this == ACTIVE || this == UPDATED;
    }
}
