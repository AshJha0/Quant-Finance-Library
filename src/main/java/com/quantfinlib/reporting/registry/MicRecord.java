package com.quantfinlib.reporting.registry;

import java.util.Objects;

/**
 * One ISO 10383 entry.
 *
 * @param mic the MIC
 * @param operatingMic the operating MIC (equal to {@code mic} for an operating entry)
 * @param name market name or institution description
 * @param status the status
 */
public record MicRecord(String mic, String operatingMic, String name, MicStatus status) {
    /** Validates non-null components. */
    public MicRecord {
        Objects.requireNonNull(mic, "mic");
        Objects.requireNonNull(operatingMic, "operatingMic");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(status, "status");
    }

    /**
     * Whether this is a segment MIC (its operating MIC differs).
     *
     * @return true for a segment
     */
    public boolean isSegment() {
        return !mic.equals(operatingMic);
    }
}
