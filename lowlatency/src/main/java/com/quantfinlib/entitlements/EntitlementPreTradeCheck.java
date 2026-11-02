package com.quantfinlib.entitlements;

import com.quantfinlib.fix.NewOrderSingle;
import com.quantfinlib.risk.PreTradeLimitChecker;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Puts an entitlement check on the order path. It answers in the same shape as
 * {@link PreTradeLimitChecker.CheckResult}, so a gate can run it first, run its
 * limit checks second, and treat both results alike.
 *
 * <pre>{@code
 * var ent = new EntitlementPreTradeCheck(service, Clock.systemUTC());
 * var r = ent.check("alice", order, "XLON", "ACC-1");
 * if (r.approved()) { r = limits.check(...); }
 * }</pre>
 *
 * The clock is injected so tests and replays decide at the event time.
 */
public final class EntitlementPreTradeCheck {
    private final EntitlementService service;
    private final Clock clock;

    public EntitlementPreTradeCheck(EntitlementService service, Clock clock) {
        this.service = Objects.requireNonNull(service, "service");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Checks a FIX NewOrderSingle routed to {@code venue} for {@code account}. */
    public PreTradeLimitChecker.CheckResult check(String user, NewOrderSingle order, String venue,
                                                  String account) {
        return check(user, Action.SUBMIT_ORDER, order.symbol(), venue, account);
    }

    /** Checks a risk-layer order request routed to {@code venue} for {@code account}. */
    public PreTradeLimitChecker.CheckResult check(String user, PreTradeLimitChecker.OrderRequest order,
                                                  String venue, String account) {
        return check(user, Action.SUBMIT_ORDER, order.symbol(), venue, account);
    }

    /** General form for any action (amend, cancel) on a concrete scope. */
    public PreTradeLimitChecker.CheckResult check(String user, Action action, String instrument,
                                                  String venue, String account) {
        Instant now = clock.instant();
        Decision d = service.decide(user, new Permission(action, instrument, venue, account), now);
        return d.allowed()
                ? new PreTradeLimitChecker.CheckResult(true, List.of())
                : new PreTradeLimitChecker.CheckResult(false,
                        List.of("ENTITLEMENT: " + d.reason() + " [audit #" + d.sequence() + "]"));
    }
}
