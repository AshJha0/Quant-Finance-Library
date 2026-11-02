package com.quantfinlib.regreporting.registry;

import java.time.LocalDate;
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

    /**
     * Whether the MIC was active on a date. The default ignores the date and uses
     * {@link MicStatus#isUsable()} on the current status. A registry that keeps the ISO
     * 10383 creation and expiry dates should override it.
     *
     * @param mic the code
     * @param date the trading date
     * @return true if active
     * @since 2.4.0
     */
    default boolean isActiveOn(String mic, LocalDate date) {
        return status(mic).isUsable();
    }

    /**
     * ISO 3166 country of the market (the ISO 10383 {@code ISO COUNTRY CODE} column).
     * The default returns empty. The ESMA rules that ask whether a venue is in the EEA
     * (CON-411, CON-471, CON-610) are then skipped for that MIC.
     *
     * @param mic the code
     * @return the country, or empty when not known
     * @since 2.4.0
     */
    default Optional<String> country(String mic) {
        return Optional.empty();
    }

    /**
     * Whether the MIC identifies a systematic internaliser (ISO 10383 market category
     * {@code SINT}, or the ESMA SI register). The default returns empty (not known).
     *
     * @param mic the code
     * @return the flag, or empty when not known
     * @since 2.4.0
     */
    default Optional<Boolean> isSystematicInternaliser(String mic) {
        return Optional.empty();
    }
}
