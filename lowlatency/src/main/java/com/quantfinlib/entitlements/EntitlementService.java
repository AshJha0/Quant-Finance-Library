package com.quantfinlib.entitlements;

import com.quantfinlib.entitlements.Grant.Effect;
import com.quantfinlib.entitlements.Grant.Subject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Users, desks, roles and grants, with a deny-overrides decision rule and an
 * append-only audit log of every decision.
 *
 * <p>Plain idea: a user may do something only if some grant that applies to
 * them allows it and no grant that applies to them forbids it. A grant applies
 * to a user directly, through the user's desk, or through any of their roles.
 *
 * <p>Formal rule, for user {@code u}, request {@code r} and time {@code t}: let
 * {@code G} be the grants whose subject is {@code u}, {@code desk(u)} or a role
 * of {@code u}, whose permission covers {@code r}, and which are in force at
 * {@code t}. The answer is DENY if any grant in {@code G} is DENY, else ALLOW if
 * any is ALLOW, else DENY. Unknown users are always denied. This is the
 * deny-overrides combining rule of OASIS XACML 3.0 (published reference) with a
 * closed-world default.
 *
 * <p>Worked example: role TRADER allows SUBMIT_ORDER on any instrument and venue
 * for account ACC-1; desk RATES has a DENY on SUBMIT_ORDER at venue XEUR. A
 * RATES trader submitting on XLON for ACC-1 is allowed by the role grant; the
 * same order on XEUR is denied by the desk grant although the role allows it
 * ({@code EntitlementServiceTest}, own test).
 *
 * <p>Thread-safe: every method takes one private lock. The audit log lives in memory;
 * making it durable is the caller's job, via {@link #auditLog()} or a
 * {@link DecisionListener}.
 */
public final class EntitlementService {

    /** Receives each decision as it is made, for example to write it to durable storage. */
    @FunctionalInterface
    public interface DecisionListener {
        /** Called synchronously, inside the service lock, after the decision is logged. */
        void onDecision(Decision decision);
    }

    private final Object lock = new Object();
    private final Map<String, String> userDesk = new HashMap<>();
    private final Map<String, Set<String>> userRoles = new HashMap<>();
    private final Map<String, Grant> grants = new LinkedHashMap<>();
    private final List<Decision> audit = new ArrayList<>();
    private final List<DecisionListener> listeners = new ArrayList<>();

    /** Creates an empty service: no users, no grants, everything denied. */
    public EntitlementService() {
    }

    /** Adds or replaces a user, the desk they sit on (empty for none) and their roles. */
    public EntitlementService addUser(String user, String desk, String... roles) {
        synchronized (lock) {
            if (user == null || user.isBlank()) {
                throw new IllegalArgumentException("user must be non-blank");
            }
            if (desk.isEmpty()) {
                userDesk.remove(user);
            } else {
                userDesk.put(user, desk);
            }
            userRoles.put(user, new LinkedHashSet<>(List.of(roles)));
            return this;
        }
    }

    /** Removes a user; later requests from them are denied. */
    public void removeUser(String user) {
        synchronized (lock) {
            userDesk.remove(user);
            userRoles.remove(user);
        }
    }

    /** Adds a grant; the id must not already be in use. */
    public EntitlementService grant(Grant grant) {
        synchronized (lock) {
            Objects.requireNonNull(grant, "grant");
            if (grants.putIfAbsent(grant.id(), grant) != null) {
                throw new IllegalArgumentException("duplicate grant id " + grant.id());
            }
            return this;
        }
    }

    /** Revokes a grant by id; returns false if there was none. */
    public boolean revoke(String grantId) {
        synchronized (lock) {
            return grants.remove(grantId) != null;
        }
    }

    /** Current grants in insertion order. */
    public List<Grant> grants() {
        synchronized (lock) {
            return List.copyOf(grants.values());
        }
    }

    /** Registers a listener called for each decision. */
    public void onDecision(DecisionListener listener) {
        synchronized (lock) {
            listeners.add(Objects.requireNonNull(listener, "listener"));
        }
    }

    /** Decides, records, and returns only the boolean answer. */
    public boolean isAllowed(String user, Permission request, Instant at) {
        return decide(user, request, at).allowed();
    }

    /**
     * Decides whether {@code user} may perform {@code request} at {@code at},
     * appends the decision to the audit log and returns it.
     */
    public Decision decide(String user, Permission request, Instant at) {
        synchronized (lock) {
            Objects.requireNonNull(user, "user");
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(at, "at");
            Decision d;
            if (!userRoles.containsKey(user)) {
                d = record(at, user, request, false, "", "unknown user");
            } else {
                Grant allow = null;
                Grant deny = null;
                for (Grant g : grants.values()) {
                    if (!appliesTo(g.subject(), user) || !g.permission().covers(request) || !g.inForceAt(at)) {
                        continue;
                    }
                    if (g.effect() == Effect.DENY) {
                        deny = g;
                        break;
                    }
                    if (allow == null) {
                        allow = g;
                    }
                }
                if (deny != null) {
                    d = record(at, user, request, false, deny.id(), "explicit deny via " + describe(deny.subject()));
                } else if (allow != null) {
                    d = record(at, user, request, true, allow.id(), "allowed via " + describe(allow.subject()));
                } else {
                    d = record(at, user, request, false, "", "no matching grant (default deny)");
                }
            }
            for (DecisionListener l : listeners) {
                l.onDecision(d);
            }
            return d;
        }
    }

    private boolean appliesTo(Subject s, String user) {
        return switch (s.type()) {
            case USER -> s.id().equals(user);
            case DESK -> s.id().equals(userDesk.get(user));
            case ROLE -> userRoles.getOrDefault(user, Set.of()).contains(s.id());
        };
    }

    private static String describe(Subject s) {
        return s.type().name().toLowerCase(Locale.ROOT) + " " + s.id();
    }

    private Decision record(Instant at, String user, Permission request, boolean allowed,
                            String grantId, String reason) {
        Decision d = new Decision(audit.size() + 1L, at, user, request, allowed, grantId, reason);
        audit.add(d);
        return d;
    }

    /** Every decision made so far, oldest first. */
    public List<Decision> auditLog() {
        synchronized (lock) {
            return List.copyOf(audit);
        }
    }
}
