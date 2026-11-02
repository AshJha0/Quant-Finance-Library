package com.quantfinlib.regreporting.registry;

import java.time.LocalDate;
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

    /**
     * The LEI as the GLEIF data stood on a date, for the ESMA rules that read the
     * status, InitialRegistrationDate, EntityStatus and LastUpdateDate (ESMA65-8-2594
     * Annex 1, CON-040 and similar). The default reads the current snapshot from
     * {@link #lookup} and reports the dates as unknown, so only the status is checked.
     * A registry that keeps GLEIF history should override it.
     *
     * @param lei the LEI
     * @param date the date the data should describe
     * @return the validity data, or empty if the registry does not know the LEI
     * @since 2.4.0
     */
    default Optional<LeiValidity> validityOn(String lei, LocalDate date) {
        return lookup(lei).map(r -> LeiValidity.statusOnly(r.status()));
    }
}
