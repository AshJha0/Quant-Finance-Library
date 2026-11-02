package com.quantfinlib.entitlements;

import java.time.Instant;
import java.util.Objects;

/**
 * One allow or deny rule. The subject is a user, a desk or a role. The window
 * is half-open, {@code [validFrom, validTo)}; use {@link Instant#MIN} and
 * {@link Instant#MAX} for an unbounded side.
 *
 * @param id         unique grant identifier, quoted in audit entries
 * @param subject    who the grant applies to
 * @param effect     allow or deny
 * @param permission the action and scope pattern
 * @param validFrom  first instant the grant is in force
 * @param validTo    first instant the grant is no longer in force
 * @param grantedBy  who created it, for the audit record
 */
public record Grant(String id, Subject subject, Effect effect, Permission permission,
                    Instant validFrom, Instant validTo, String grantedBy) {

    /** Allow or deny. */
    public enum Effect { ALLOW, DENY }

    /** Kind of subject. */
    public enum SubjectType { USER, DESK, ROLE }

    /**
     * A user, desk or role identifier.
     *
     * @param type kind of subject
     * @param id   its identifier
     */
    public record Subject(SubjectType type, String id) {
        public Subject {
            Objects.requireNonNull(type, "type");
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("subject id must be non-blank");
            }
        }

        public static Subject user(String id) {
            return new Subject(SubjectType.USER, id);
        }

        public static Subject desk(String id) {
            return new Subject(SubjectType.DESK, id);
        }

        public static Subject role(String id) {
            return new Subject(SubjectType.ROLE, id);
        }
    }

    public Grant {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("grant id must be non-blank");
        }
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(effect, "effect");
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(grantedBy, "grantedBy");
        Objects.requireNonNull(validFrom, "validFrom");
        Objects.requireNonNull(validTo, "validTo");
        if (!validTo.isAfter(validFrom)) {
            throw new IllegalArgumentException("validTo must be after validFrom");
        }
    }

    /** A grant with no time bounds. */
    public static Grant of(String id, Subject subject, Effect effect, Permission permission,
                           String grantedBy) {
        return new Grant(id, subject, effect, permission, Instant.MIN, Instant.MAX, grantedBy);
    }

    /** True when the grant is in force at {@code t}. */
    public boolean inForceAt(Instant t) {
        return !t.isBefore(validFrom) && t.isBefore(validTo);
    }
}
