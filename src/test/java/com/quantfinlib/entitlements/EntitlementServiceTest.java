package com.quantfinlib.entitlements;

import com.quantfinlib.entitlements.Grant.Effect;
import com.quantfinlib.entitlements.Grant.Subject;
import com.quantfinlib.fix.NewOrderSingle;
import com.quantfinlib.orderbook.Side;
import com.quantfinlib.risk.PreTradeLimitChecker;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntitlementServiceTest {
    private static final Instant T0 = Instant.parse("2026-11-02T09:00:00Z");
    private static final String ANY = Permission.ANY;

    private static Permission submit(String instrument, String venue, String account) {
        return new Permission(Action.SUBMIT_ORDER, instrument, venue, account);
    }

    private static EntitlementService rates() {
        return new EntitlementService()
                .addUser("alice", "RATES", "TRADER")
                .addUser("bob", "EQUITY", "TRADER")
                .grant(Grant.of("g-role", Subject.role("TRADER"), Effect.ALLOW, submit(ANY, ANY, "ACC-1"), "admin"))
                .grant(Grant.of("g-desk-deny", Subject.desk("RATES"), Effect.DENY, submit(ANY, "XEUR", ANY), "admin"));
    }

    @Test
    void roleAllowsAndDeskDenyOverrides() {
        EntitlementService s = rates();
        Decision ok = s.decide("alice", submit("GB00", "XLON", "ACC-1"), T0);
        assertTrue(ok.allowed());
        assertEquals("g-role", ok.decidingGrant());
        Decision no = s.decide("alice", submit("GB00", "XEUR", "ACC-1"), T0);
        assertFalse(no.allowed());
        assertEquals("g-desk-deny", no.decidingGrant());
        // bob is on another desk, so the RATES deny does not reach him.
        assertTrue(s.isAllowed("bob", submit("GB00", "XEUR", "ACC-1"), T0));
    }

    @Test
    void denyWinsRegardlessOfGrantOrder() {
        EntitlementService s = new EntitlementService().addUser("u", "")
                .grant(Grant.of("deny", Subject.user("u"), Effect.DENY, submit("X", ANY, ANY), "a"))
                .grant(Grant.of("allow", Subject.user("u"), Effect.ALLOW, submit(ANY, ANY, ANY), "a"));
        assertFalse(s.isAllowed("u", submit("X", "XLON", "A"), T0));
        assertTrue(s.isAllowed("u", submit("Y", "XLON", "A"), T0));
    }

    @Test
    void defaultDenyAndUnknownUser() {
        EntitlementService s = rates();
        Decision d = s.decide("alice", submit("GB00", "XLON", "ACC-2"), T0);
        assertFalse(d.allowed());
        assertEquals("", d.decidingGrant());
        assertFalse(s.isAllowed("mallory", submit("GB00", "XLON", "ACC-1"), T0));
        assertEquals("unknown user", s.auditLog().get(1).reason());
        // a different action is not covered by a SUBMIT grant
        assertFalse(s.isAllowed("alice", new Permission(Action.CANCEL_ORDER, "GB00", "XLON", "ACC-1"), T0));
    }

    @Test
    void timeBoundedGrantIsHalfOpen() {
        Instant from = T0;
        Instant to = T0.plusSeconds(3600);
        EntitlementService s = new EntitlementService().addUser("u", "")
                .grant(new Grant("temp", Subject.user("u"), Effect.ALLOW, submit(ANY, ANY, ANY), from, to, "head"));
        Permission p = submit("X", "XLON", "A");
        assertFalse(s.isAllowed("u", p, from.minusNanos(1)));
        assertTrue(s.isAllowed("u", p, from));
        assertTrue(s.isAllowed("u", p, to.minusNanos(1)));
        assertFalse(s.isAllowed("u", p, to));
        assertThrows(IllegalArgumentException.class,
                () -> new Grant("bad", Subject.user("u"), Effect.ALLOW, p, to, from, "x"));
    }

    @Test
    void expiredDenyNoLongerBlocks() {
        EntitlementService s = rates().grant(new Grant("halt", Subject.user("alice"), Effect.DENY,
                submit(ANY, ANY, ANY), Instant.MIN, T0.plusSeconds(60), "risk"));
        assertFalse(s.isAllowed("alice", submit("X", "XLON", "ACC-1"), T0));
        assertTrue(s.isAllowed("alice", submit("X", "XLON", "ACC-1"), T0.plusSeconds(60)));
    }

    @Test
    void revokeAndRemoveUserTakeEffect() {
        EntitlementService s = rates();
        assertTrue(s.revoke("g-desk-deny"));
        assertFalse(s.revoke("g-desk-deny"));
        assertTrue(s.isAllowed("alice", submit("X", "XEUR", "ACC-1"), T0));
        s.removeUser("alice");
        assertFalse(s.isAllowed("alice", submit("X", "XLON", "ACC-1"), T0));
        assertThrows(IllegalArgumentException.class,
                () -> s.grant(Grant.of("g-role", Subject.user("x"), Effect.ALLOW, submit(ANY, ANY, ANY), "a")));
    }

    @Test
    void auditLogRecordsEveryDecisionInOrderAndNotifiesListeners() {
        EntitlementService s = rates();
        List<Decision> seen = new ArrayList<>();
        s.onDecision(seen::add);
        s.decide("alice", submit("A", "XLON", "ACC-1"), T0);
        s.decide("alice", submit("A", "XEUR", "ACC-1"), T0.plusSeconds(1));
        List<Decision> log = s.auditLog();
        assertEquals(2, log.size());
        assertEquals(log, seen);
        assertEquals(1, log.get(0).sequence());
        assertEquals(2, log.get(1).sequence());
        assertEquals("2 2026-11-02T09:00:01Z user=alice SUBMIT_ORDER instrument=A venue=XEUR account=ACC-1"
                + " -> DENY grant=g-desk-deny (explicit deny via desk RATES)", log.get(1).format());
    }

    @Test
    void preTradeAdapterReturnsCheckResult() {
        EntitlementService s = rates();
        var check = new EntitlementPreTradeCheck(s, Clock.fixed(T0, ZoneOffset.UTC));
        var nos = new NewOrderSingle("c1", "GB00", Side.BUY, 100, NewOrderSingle.ORD_TYPE_LIMIT, 10.0,
                NewOrderSingle.TIF_DAY);
        assertTrue(check.check("alice", nos, "XLON", "ACC-1").approved());
        PreTradeLimitChecker.CheckResult r = check.check("alice", nos, "XEUR", "ACC-1");
        assertFalse(r.approved());
        assertEquals("ENTITLEMENT: explicit deny via desk RATES [audit #2]", r.violations().get(0));
        var req = new PreTradeLimitChecker.OrderRequest("GB00", Side.SELL, 5, 10.0, "CP");
        assertTrue(check.check("bob", req, "XEUR", "ACC-1").approved());
        assertEquals(T0, s.auditLog().get(2).at());
    }

    @Test
    void permissionRejectsBlankScope() {
        assertThrows(IllegalArgumentException.class, () -> submit("", "XLON", "A"));
    }
}
