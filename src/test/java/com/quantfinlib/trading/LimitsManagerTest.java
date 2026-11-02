package com.quantfinlib.trading;

import com.quantfinlib.orderbook.Side;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LimitsManagerTest {

    private static final String TEXT = """
            # desk limits, reviewed 2026-11-01
            maxOrderQuantity      = 10000
            maxOrderNotional      = 5e6
            maxPositionQuantity   = 50000
            priceCollarPct        = 0.02   # 2 %
            maxDailyNotional      = 1e9
            maxDailyOrders        = 200000
            maxDailyLoss          = 250000
            duplicateWindowNanos  = 1000000
            requireReferencePrice = true
            """;

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-01T08:00:00Z"), ZoneOffset.UTC);

    @Test
    void parseFormatRoundTrip() {
        LimitsConfig c = LimitsConfig.parse(TEXT);
        assertEquals(10_000, c.maxOrderQuantity());
        assertEquals(5e6, c.maxOrderNotional());
        assertEquals(0.02, c.priceCollarPct());
        assertTrue(c.requireReferencePrice());
        assertEquals(c, LimitsConfig.parse(c.format()));
        assertEquals(LimitsConfig.UNLIMITED, LimitsConfig.parse(LimitsConfig.UNLIMITED.format()));
        assertTrue(LimitsConfig.UNLIMITED.format().contains("maxOrderQuantity = unlimited"));
        assertEquals(LimitsConfig.UNLIMITED, LimitsConfig.of(new HftRiskGate(1)));
    }

    @Test
    void parseRejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> LimitsConfig.parse(TEXT.replace("maxDailyLoss          = 250000\n", "")),
                "a missing key is an error");
        assertThrows(IllegalArgumentException.class, () -> LimitsConfig.parse(TEXT + "maxDailyLoss = 1\n"));
        assertThrows(IllegalArgumentException.class, () -> LimitsConfig.parse(TEXT + "maxFoo = 1\n"));
        assertThrows(IllegalArgumentException.class, () -> LimitsConfig.parse(TEXT + "garbage\n"));
        assertThrows(IllegalArgumentException.class, () -> LimitsConfig.parse(TEXT.replace("maxOrderQuantity      = 10000", "maxOrderQuantity = ten")));
        assertThrows(IllegalArgumentException.class, () -> LimitsConfig.parse(TEXT.replace("= 5e6", "= abc")));
        assertThrows(IllegalArgumentException.class, () -> LimitsConfig.parse(TEXT.replace("= 5e6", "= Infinity")));
        assertThrows(IllegalArgumentException.class, () -> LimitsConfig.parse(TEXT.replace("= 0.02", "= 1.5")));
        assertThrows(IllegalArgumentException.class, () -> LimitsConfig.parse(TEXT.replace("= 10000\n", "= 0\n")));
        assertThrows(IllegalArgumentException.class, () -> LimitsConfig.parse(TEXT.replace("= true", "= yes")));
        assertThrows(IllegalArgumentException.class, () -> LimitsConfig.parse(TEXT.replace("= 1000000", "= -1")));
        assertThrows(IllegalArgumentException.class, () -> LimitsConfig.parse(TEXT.replace("= 1000000", "= unlimited")));
        assertThrows(IllegalArgumentException.class, () -> LimitsConfig.parse(TEXT.replace("= 250000", "= -5")));
    }

    @Test
    void tightestAndOrdering() {
        LimitsConfig a = LimitsConfig.parse(TEXT);
        LimitsConfig b = new LimitsConfig(10, 1e7, 60_000, 0.05, 1e8, 300_000, 100_000, 0, false);
        LimitsConfig t = LimitsConfig.tightest(a, b);
        assertEquals(new LimitsConfig(10, 5e6, 50_000, 0.02, 1e8, 200_000, 100_000, 1_000_000, true), t);
        assertTrue(t.isNoLooserThan(a) && t.isNoLooserThan(b));
        assertFalse(a.isNoLooserThan(b));
        assertEquals(List.of("maxOrderQuantity: 10000 -> 10"), t.changesFrom(new LimitsConfig(
                10_000, 5e6, 50_000, 0.02, 1e8, 200_000, 100_000, 1_000_000, true)));
    }

    @Test
    void applyAuditAndRollbackAcrossGates() {
        HftRiskGate g1 = new HftRiskGate(4);
        HftRiskGate g2 = new HftRiskGate(4);
        LimitsManager m = new LimitsManager(List.of(g1, g2), LimitsConfig.parse(TEXT), "risk-ops",
                "start of day", CLOCK);
        assertEquals(1, m.currentVersion());
        assertEquals(10_000, g2.maxOrderQuantity());
        assertEquals(0.02, g1.priceCollarPct());

        int v2 = m.apply(TEXT.replace("maxOrderQuantity      = 10000", "maxOrderQuantity = 2000"), "alice", "tighten after alert");
        assertEquals(2, v2);
        assertEquals(2000, g1.maxOrderQuantity());
        assertEquals(2000, g2.maxOrderQuantity());
        assertEquals(HftRiskGate.REJECT_QUANTITY, g1.check(0, Side.BUY, 5000, 10.0));

        int v3 = m.rollback(1, "bob", "alert cleared");
        assertEquals(3, v3);
        assertEquals(10_000, g1.maxOrderQuantity());
        assertEquals(m.config(1), m.current());

        List<LimitsManager.AuditEntry> trail = m.auditTrail();
        assertEquals(3, trail.size());
        assertEquals(LimitsManager.Action.INITIAL, trail.get(0).action());
        assertEquals(List.of("maxOrderQuantity: 10000 -> 2000"), trail.get(1).changes());
        assertEquals("alice", trail.get(1).author());
        assertEquals(1, trail.get(1).previousVersion());
        assertEquals(LimitsManager.Action.ROLLBACK, trail.get(2).action());
        assertEquals(1, trail.get(2).restoredVersion());
        assertEquals(List.of("maxOrderQuantity: 2000 -> 10000"), trail.get(2).changes());
        assertEquals(CLOCK.instant(), trail.get(2).at());
        assertTrue(trail.get(2).format().startsWith("v3 ROLLBACK to v1 by bob"), trail.get(2).format());
        assertTrue(trail.get(0).changes().contains("maxOrderQuantity: unlimited -> 10000"));

        assertThrows(IllegalArgumentException.class, () -> m.rollback(9, "bob", "x"));
        assertThrows(IllegalArgumentException.class, () -> m.apply(TEXT, " ", "x"));
        assertThrows(IllegalArgumentException.class, () -> m.apply(TEXT, "bob", ""));
        assertThrows(IllegalArgumentException.class, () -> m.apply("maxOrderQuantity = 1", "bob", "x"));
        assertEquals(3, m.currentVersion(), "failed changes leave no version");
        assertThrows(IllegalArgumentException.class,
                () -> new LimitsManager(List.of(), LimitsConfig.UNLIMITED, "a", "b", CLOCK));
    }

    /**
     * Old config refuses the probe order on notional, new config on
     * quantity; a field-by-field write could briefly allow it (old quantity
     * cap, new notional cap). The single-object swap never does.
     */
    @Test
    void concurrentChecksNeverSeeALooserMix() throws Exception {
        LimitsConfig oldC = new LimitsConfig(1000, 1000, Long.MAX_VALUE, Double.MAX_VALUE,
                Double.MAX_VALUE, Long.MAX_VALUE, Double.MAX_VALUE, 0, false);
        LimitsConfig newC = new LimitsConfig(10, 1e6, Long.MAX_VALUE, Double.MAX_VALUE,
                Double.MAX_VALUE, Long.MAX_VALUE, Double.MAX_VALUE, 0, false);
        HftRiskGate gate = new HftRiskGate(1);
        LimitsManager m = new LimitsManager(List.of(gate), oldC, "t", "t", CLOCK);
        AtomicBoolean stop = new AtomicBoolean();
        AtomicLong accepted = new AtomicLong();
        AtomicLong checks = new AtomicLong();
        Thread checker = new Thread(() -> {
            while (!stop.get()) {
                if (gate.check(0, Side.BUY, 500, 10.0) == HftRiskGate.OK) {
                    accepted.incrementAndGet();
                }
                checks.incrementAndGet();
            }
        });
        checker.start();
        for (int i = 0; i < 20_000; i++) {
            m.apply(i % 2 == 0 ? newC : oldC, "t", "flip");
        }
        stop.set(true);
        checker.join();
        assertEquals(0, accepted.get(), "checks run: " + checks.get());
    }
}
