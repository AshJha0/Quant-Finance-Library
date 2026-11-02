package com.quantfinlib.regreporting.registry;

import java.time.LocalDate;
import java.util.Objects;

/**
 * One ISO 10383 entry.
 *
 * @param mic the MIC
 * @param operatingMic the operating MIC (equal to {@code mic} for an operating entry)
 * @param name market name or institution description
 * @param status the status
 * @param country ISO 3166 country code, empty when not known
 * @param marketCategory ISO 10383 market category code (for example {@code RMKT},
 *        {@code MLTF}, {@code OTFS}, {@code SINT}), empty when not known
 * @param creationDate date the MIC was created, or null when not known
 * @param expiryDate date the MIC expired, or null when it has not expired or is not known
 */
public record MicRecord(String mic, String operatingMic, String name, MicStatus status, String country,
        String marketCategory, LocalDate creationDate, LocalDate expiryDate) {
    /** ISO 10383 market category code of a systematic internaliser. */
    public static final String SYSTEMATIC_INTERNALISER = "SINT";

    /** Validates non-null components. */
    public MicRecord {
        Objects.requireNonNull(mic, "mic");
        Objects.requireNonNull(operatingMic, "operatingMic");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(country, "country");
        Objects.requireNonNull(marketCategory, "marketCategory");
    }

    /**
     * An entry with status only (no country, category or dates), the 2.4 shape.
     *
     * @param mic the MIC
     * @param operatingMic the operating MIC
     * @param name market name
     * @param status the status
     */
    public MicRecord(String mic, String operatingMic, String name, MicStatus status) {
        this(mic, operatingMic, name, status, "", "", null, null);
    }

    /**
     * Whether this is a segment MIC (its operating MIC differs).
     *
     * @return true for a segment
     */
    public boolean isSegment() {
        return !mic.equals(operatingMic);
    }

    /**
     * Whether the market category is {@code SINT} (systematic internaliser).
     *
     * @return true for a systematic internaliser
     */
    public boolean isSystematicInternaliser() {
        return SYSTEMATIC_INTERNALISER.equals(marketCategory);
    }

    /**
     * Whether the MIC was in force on a date. With a creation or expiry date on the record,
     * the MIC is in force from the creation date up to the day before the expiry date. The
     * ISO 10383 Release 2.0 factsheet says the expiry date "is populated when the MIC is
     * deactivated" (published reference); that the MIC is no longer valid on that day itself
     * is the library's reading, since the factsheet does not say whether the day counts.
     * Without either date the current status decides, as in 2.4.
     *
     * @param date the date
     * @return true if in force on that date
     */
    public boolean isActiveOn(LocalDate date) {
        Objects.requireNonNull(date, "date");
        if (creationDate == null && expiryDate == null) {
            return status.isUsable();
        }
        boolean started = creationDate == null || !date.isBefore(creationDate);
        boolean ended = expiryDate != null && !date.isBefore(expiryDate);
        return status != MicStatus.UNKNOWN && started && !ended;
    }
}
