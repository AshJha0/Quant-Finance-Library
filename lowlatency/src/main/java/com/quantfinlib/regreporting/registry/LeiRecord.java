package com.quantfinlib.regreporting.registry;

import java.util.Objects;

/**
 * One LEI record as far as reporting needs it.
 *
 * @param lei the 20-character LEI
 * @param legalName the entity legal name
 * @param status the registration status
 * @param entityCategory the GLEIF entity category ({@code GENERAL}, {@code BRANCH},
 *        {@code FUND}, {@code SOLE_PROPRIETOR}, {@code RESIDENT_GOVERNMENT_ENTITY},
 *        {@code INTERNATIONAL_ORGANIZATION}; LEI-CDF 3.1 XSD, {@code EntityCategoryTypeEnum}),
 *        upper case, empty when not known (since 3.1.0)
 */
public record LeiRecord(String lei, String legalName, LeiStatus status, String entityCategory) {
    /** The GLEIF entity category of a branch (LEI-CDF 3.1 XSD, {@code EntityCategoryTypeEnum}). */
    public static final String BRANCH = "BRANCH";

    /** Validates non-null components and upper-cases the category. */
    public LeiRecord {
        Objects.requireNonNull(lei, "lei");
        Objects.requireNonNull(legalName, "legalName");
        Objects.requireNonNull(status, "status");
        entityCategory = Objects.requireNonNull(entityCategory, "entityCategory").trim()
                .toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * A record without an entity category, the 3.0 shape.
     *
     * @param lei the LEI
     * @param legalName the legal name
     * @param status the registration status
     */
    public LeiRecord(String lei, String legalName, LeiStatus status) {
        this(lei, legalName, status, "");
    }

    /**
     * Whether GLEIF records this LEI as a branch, empty when the category is not known.
     *
     * @return true for a branch, false for any other known category, empty when unknown
     * @since 3.1.0
     */
    public java.util.Optional<Boolean> isBranch() {
        return entityCategory.isEmpty() ? java.util.Optional.empty()
                : java.util.Optional.of(BRANCH.equals(entityCategory));
    }
}
