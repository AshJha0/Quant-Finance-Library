package com.quantfinlib.reporting.registry;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * What the ESMA LEI rules read from GLEIF for one LEI on one date (ESMA65-8-2594
 * Annex 1, CON-040, CON-041, CON-060, CON-070 and the other LEI rules): the
 * registration status, the InitialRegistrationDate, whether the EntityStatus is
 * Active, and the LastUpdateDate. A date may be unknown, and the check that needs
 * it is then skipped.
 *
 * @param status the registration status
 * @param initialRegistration the InitialRegistrationDate, or null when not known
 * @param entityActive whether EntityStatus is ACTIVE
 * @param lastUpdate the LastUpdateDate, or null when not known
 * @since 2.4.0
 */
public record LeiValidity(LeiStatus status, LocalDate initialRegistration, boolean entityActive,
        LocalDate lastUpdate) {
    /** Validates the status. */
    public LeiValidity {
        Objects.requireNonNull(status, "status");
    }

    /**
     * Status only: entity assumed active, dates unknown.
     *
     * @param status the status
     * @return the validity
     */
    public static LeiValidity statusOnly(LeiStatus status) {
        return new LeiValidity(status, null, true, null);
    }

    /**
     * The InitialRegistrationDate if known.
     *
     * @return the date, or empty
     */
    public Optional<LocalDate> initialRegistrationDate() {
        return Optional.ofNullable(initialRegistration);
    }

    /**
     * The LastUpdateDate if known.
     *
     * @return the date, or empty
     */
    public Optional<LocalDate> lastUpdateDate() {
        return Optional.ofNullable(lastUpdate);
    }
}
