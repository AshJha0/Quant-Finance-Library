package com.quantfinlib.reporting.registry;

import java.util.Optional;

/** Looks up LEI records. */
public interface LeiRegistry {
    /**
     * Finds a record.
     *
     * @param lei the LEI
     * @return the record, or empty if the registry does not know it
     */
    Optional<LeiRecord> lookup(String lei);

    /**
     * Status of an LEI, {@link LeiStatus#UNKNOWN} when not found.
     *
     * @param lei the LEI
     * @return the status
     */
    default LeiStatus status(String lei) {
        return lookup(lei).map(LeiRecord::status).orElse(LeiStatus.UNKNOWN);
    }
}
