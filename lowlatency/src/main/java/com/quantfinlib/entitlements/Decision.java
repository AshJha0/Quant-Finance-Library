package com.quantfinlib.entitlements;

import java.time.Instant;
import java.util.Objects;

/**
 * The answer to one entitlement question, as written to the audit log.
 *
 * @param sequence      1-based position in the audit log
 * @param at            the instant the question was asked for
 * @param user          who asked
 * @param request       the concrete action and scope
 * @param allowed       the answer
 * @param decidingGrant id of the grant that settled it, or empty when no grant
 *                      matched (default deny, or unknown user)
 * @param reason        short human-readable explanation
 */
public record Decision(long sequence, Instant at, String user, Permission request, boolean allowed,
                       String decidingGrant, String reason) {
    public Decision {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(decidingGrant, "decidingGrant");
        Objects.requireNonNull(reason, "reason");
    }

    /** One-line audit form. */
    public String format() {
        return sequence + " " + at + " user=" + user + " " + request.action()
                + " instrument=" + request.instrument() + " venue=" + request.venue()
                + " account=" + request.account() + " -> " + (allowed ? "ALLOW" : "DENY")
                + " grant=" + (decidingGrant.isEmpty() ? "-" : decidingGrant) + " (" + reason + ")";
    }
}
