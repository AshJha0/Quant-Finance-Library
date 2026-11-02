package com.quantfinlib.reporting.registry;

import java.util.Optional;

/** Looks up ISO 10383 market identifier codes. */
public interface MicRegistry {
    /**
     * Finds an entry.
     *
     * @param mic the code
     * @return the entry, or empty
     */
    Optional<MicRecord> lookup(String mic);

    /**
     * Status, {@link MicStatus#UNKNOWN} when not found.
     *
     * @param mic the code
     * @return the status
     */
    default MicStatus status(String mic) {
        return lookup(mic).map(MicRecord::status).orElse(MicStatus.UNKNOWN);
    }
}
