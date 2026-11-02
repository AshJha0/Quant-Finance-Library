package com.quantfinlib.regreporting.registry;

import java.util.Objects;

/**
 * One LEI record as far as reporting needs it.
 *
 * @param lei the 20-character LEI
 * @param legalName the entity legal name
 * @param status the registration status
 */
public record LeiRecord(String lei, String legalName, LeiStatus status) {
    /** Validates non-null components. */
    public LeiRecord {
        Objects.requireNonNull(lei, "lei");
        Objects.requireNonNull(legalName, "legalName");
        Objects.requireNonNull(status, "status");
    }
}
