package com.quantfinlib.entitlements;

import java.util.Objects;

/**
 * An action on a scope of instrument, venue and account. Each scope field is
 * either an exact identifier or {@link #ANY} ({@code "*"}). Read as a pattern,
 * a permission with wildcards covers every concrete request it matches.
 *
 * <p>Example: {@code new Permission(Action.SUBMIT_ORDER, "*", "XLON", "ACC-1")}
 * covers submitting any instrument on XLON for account ACC-1.
 *
 * @param action     what is being done
 * @param instrument instrument identifier (symbol or ISIN), or "*"
 * @param venue      venue (for example a MIC), or "*"
 * @param account    account identifier, or "*"
 */
public record Permission(Action action, String instrument, String venue, String account) {
    /** Wildcard that matches any value in a scope field. */
    public static final String ANY = "*";

    public Permission {
        Objects.requireNonNull(action, "action");
        requireText(instrument, "instrument");
        requireText(venue, "venue");
        requireText(account, "account");
    }

    private static void requireText(String s, String name) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank (use \"*\" for any)");
        }
    }

    /** True when this permission, read as a pattern, covers {@code request}. */
    public boolean covers(Permission request) {
        return action == request.action
                && field(instrument, request.instrument)
                && field(venue, request.venue)
                && field(account, request.account);
    }

    private static boolean field(String pattern, String value) {
        return ANY.equals(pattern) || pattern.equals(value);
    }
}
