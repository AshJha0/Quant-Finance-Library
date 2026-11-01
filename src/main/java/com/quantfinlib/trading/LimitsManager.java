package com.quantfinlib.trading;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Versioned limits for one or more running {@link HftRiskGate}s: every change
 * is validated, numbered, applied to all gates and recorded with who made it,
 * when, why and what moved, and any earlier version can be restored.
 *
 * <h2>Applying to a running gate</h2>
 * <p>Writing a new config field by field would let a check running at the
 * same moment see some old and some new limits, a combination that can be
 * looser than both (the old quantity cap with the new notional cap, say).
 * The manager instead swaps the whole set with
 * {@link HftRiskGate#limits(LimitsConfig)}: the gate keeps its limits in one
 * immutable object and reads it with a single acquire load per check, so
 * every check runs entirely under one version. With several gates the swaps
 * run gate by gate under one lock; different gates may briefly hold
 * different versions.</p>
 *
 * <h2>Audit</h2>
 * <p>Versions start at 1 (the initial config) and increase by one per
 * change; a rollback creates a new version whose config equals the restored
 * one, so the history only grows. {@link #auditTrail()} lists every entry in
 * order. RTS 6 Art. 15(2) (MiFID II) expects pre-trade limits to be
 * adjustable intraday under documented procedures; this is the record such a
 * procedure keeps.</p>
 *
 * <p>Thread-safe: changes are serialised on this object.</p>
 */
public final class LimitsManager {

    /** Kind of audit entry. */
    public enum Action { INITIAL, APPLY, ROLLBACK }

    /**
     * One recorded change.
     *
     * @param version         the version this change created
     * @param previousVersion the version it replaced (0 for the initial entry)
     * @param restoredVersion for a rollback, the version whose config was restored; else 0
     * @param changes         {@code key: old -> new} lines; empty if nothing moved
     */
    public record AuditEntry(int version, int previousVersion, int restoredVersion, Action action,
                             String author, Instant at, String reason, List<String> changes) {
        public AuditEntry {
            changes = List.copyOf(changes);
        }

        /** One line: {@code v3 APPLY by ops1 at ...: reason [changes]}. */
        public String format() {
            return "v" + version + " " + action
                    + (restoredVersion > 0 ? " to v" + restoredVersion : "")
                    + " by " + author + " at " + at + ": " + reason + " " + changes;
        }
    }

    private final List<HftRiskGate> gates;
    private final Clock clock;
    private final List<LimitsConfig> versions = new ArrayList<>();   // index = version - 1
    private final List<AuditEntry> audit = new ArrayList<>();

    /**
     * Applies {@code initial} to every gate as version 1.
     *
     * @param clock time source for the audit trail
     */
    public LimitsManager(List<HftRiskGate> gates, LimitsConfig initial, String author, String reason,
                         Clock clock) {
        this.gates = List.copyOf(gates);
        if (this.gates.isEmpty()) {
            throw new IllegalArgumentException("at least one gate");
        }
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(initial, "initial");
        List<String> changes = initial.changesFrom(LimitsConfig.of(this.gates.getFirst()));
        for (HftRiskGate g : this.gates) {
            write(g, initial);
        }
        versions.add(initial);
        audit.add(new AuditEntry(1, 0, 0, Action.INITIAL, requireText(author, "author"),
                clock.instant(), requireText(reason, "reason"), changes));
    }

    /** Validates and applies {@code next}; returns the new version number. */
    public synchronized int apply(LimitsConfig next, String author, String reason) {
        return change(Objects.requireNonNull(next, "next"), Action.APPLY, 0, author, reason);
    }

    /** Parses the text format ({@link LimitsConfig#parse}) and applies it. */
    public synchronized int apply(String text, String author, String reason) {
        return apply(LimitsConfig.parse(text), author, reason);
    }

    /** Restores the config of {@code version} as a new version; returns that new number. */
    public synchronized int rollback(int version, String author, String reason) {
        return change(config(version), Action.ROLLBACK, version, author, reason);
    }

    private int change(LimitsConfig next, Action action, int restored, String author, String reason) {
        requireText(author, "author");
        requireText(reason, "reason");
        LimitsConfig current = current();
        for (HftRiskGate g : gates) {
            write(g, next);
        }
        versions.add(next);
        int v = versions.size();
        audit.add(new AuditEntry(v, v - 1, restored, action, author, clock.instant(), reason,
                next.changesFrom(current)));
        return v;
    }

    private static void write(HftRiskGate gate, LimitsConfig to) {
        to.applyTo(gate);
    }

    private static String requireText(String s, String what) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException(what + " is required");
        }
        return s;
    }

    /** The config in force. */
    public synchronized LimitsConfig current() {
        return versions.getLast();
    }

    public synchronized int currentVersion() {
        return versions.size();
    }

    /** The config of a recorded version. */
    public synchronized LimitsConfig config(int version) {
        if (version < 1 || version > versions.size()) {
            throw new IllegalArgumentException("no limits version " + version);
        }
        return versions.get(version - 1);
    }

    /** Every change in order, oldest first. */
    public synchronized List<AuditEntry> auditTrail() {
        return List.copyOf(audit);
    }
}
