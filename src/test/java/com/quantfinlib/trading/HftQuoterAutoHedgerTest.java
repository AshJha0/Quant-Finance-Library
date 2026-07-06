package com.quantfinlib.trading;

import com.quantfinlib.microstructure.TickSizeSchedule;
import com.quantfinlib.orderbook.Side;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The streaming quoter (spread, inventory skew, grid snap, conflation,
 * risk-gate refusal) and the band auto-hedger (breach detection, hedge
 * sizing to the band edge, cooldown, rejection accounting) — both driven
 * through a live gateway with the venue side captured.
 */
class HftQuoterAutoHedgerTest {

    /** One captured venue-side order (primitive fields boxed for asserts). */
    private record Captured(long id, int symbolId, Side side, long qty, double price) {
    }

    private static HftRiskGate openGate() {
        HftRiskGate gate = new HftRiskGate(16)
                .maxOrderQuantity(10_000_000)
                .maxOrderNotional(1e15)
                .maxPositionQuantity(Long.MAX_VALUE / 4)
                .priceCollarPct(0.50);
        gate.setReferencePrice(0, 1.0850);
        return gate;
    }

    /** Captures venue-side deliveries with synchronization for test asserts. */
    private static List<Captured> capture(HftOrderGateway gateway) {
        List<Captured> orders = java.util.Collections.synchronizedList(new ArrayList<>());
        gateway.addOrderListener((id, sym, side, qty, px, ts) ->
                orders.add(new Captured(id, sym, side, qty, px)));
        return orders;
    }

    private static void awaitCount(List<?> list, int n) throws InterruptedException {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (list.size() < n) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("expected " + n + " orders, saw " + list.size());
            }
            Thread.sleep(1);
        }
    }

    // ------------------------------------------------------------------
    // HftQuoter
    // ------------------------------------------------------------------

    @Test
    void quotesBothSidesAroundTheMid() throws Exception {
        try (HftOrderGateway gateway = new HftOrderGateway(1 << 10, openGate(), false)) {
            List<Captured> orders = capture(gateway);
            gateway.start();
            HftQuoter quoter = new HftQuoter(gateway, 16,
                    HftQuoter.Config.of(100_000, 0.0002));

            quoter.onTick(0, 1.0850, 1_000_000, System.nanoTime());
            awaitCount(orders, 2);

            assertEquals(1, quoter.quoteUpdates());
            Captured bid = orders.get(0);
            Captured ask = orders.get(1);
            assertEquals(Side.BUY, bid.side());
            assertEquals(Side.SELL, ask.side());
            assertEquals(1.0850 - 0.0002, bid.price(), 1e-12);
            assertEquals(1.0850 + 0.0002, ask.price(), 1e-12);
            assertEquals(100_000, bid.qty());
        }
    }

    @Test
    void inventorySkewShadesTheQuoteAgainstThePosition() throws Exception {
        HftRiskGate gate = openGate();
        try (HftOrderGateway gateway = new HftOrderGateway(1 << 10, gate, false)) {
            List<Captured> orders = capture(gateway);
            gateway.start();
            HftQuoter quoter = new HftQuoter(gateway, 16,
                    HftQuoter.Config.of(100_000, 0.0002).withSkewPerUnit(1e-9));

            // Long 1m units: skew = −1e6 × 1e-9 = −0.001 — both sides DOWN,
            // making our offer attractive so the market takes our excess.
            gate.onFill(0, Side.BUY, 1_000_000);
            quoter.onTick(0, 1.0850, 1_000_000, System.nanoTime());
            awaitCount(orders, 2);
            assertEquals(1.0850 - 0.0002 - 0.001, orders.get(0).price(), 1e-12);
            assertEquals(1.0850 + 0.0002 - 0.001, orders.get(1).price(), 1e-12);
            // Spread width is preserved by pure skew.
            assertEquals(0.0004, orders.get(1).price() - orders.get(0).price(), 1e-12);
        }
    }

    @Test
    void gridSnapRoundsTowardPassivity() throws Exception {
        try (HftOrderGateway gateway = new HftOrderGateway(1 << 10, openGate(), false)) {
            List<Captured> orders = capture(gateway);
            gateway.start();
            HftQuoter quoter = new HftQuoter(gateway, 16, HftQuoter.Config
                    .of(100_000, 0.00017) // produces off-grid raw quotes
                    .withTickSchedule(TickSizeSchedule.flat(0.0001)));

            quoter.onTick(0, 1.0850, 1_000_000, System.nanoTime());
            awaitCount(orders, 2);
            // Raw bid 1.08483 → down to 1.0848; raw ask 1.08517 → up to 1.0852.
            assertEquals(1.0848, orders.get(0).price(), 1e-12);
            assertEquals(1.0852, orders.get(1).price(), 1e-12);
        }
    }

    @Test
    void conflationSuppressesNoiseButPassesMovesAndTime() throws Exception {
        try (HftOrderGateway gateway = new HftOrderGateway(1 << 10, openGate(), false)) {
            List<Captured> orders = capture(gateway);
            gateway.start();
            HftQuoter quoter = new HftQuoter(gateway, 16, HftQuoter.Config
                    .of(100_000, 0.0002)
                    .withConflation(1_000_000_000L, 0.0005)); // 1s / 5 pips

            long t0 = 1_000_000_000L;
            quoter.onTick(0, 1.0850, 1e6, t0);              // first quote: always out
            quoter.onTick(0, 1.08501, 1e6, t0 + 1000);      // tiny move, young: suppressed
            quoter.onTick(0, 1.0860, 1e6, t0 + 2000);       // 10 pips: passes on move
            quoter.onTick(0, 1.08601, 1e6, t0 + 2_000_000_000L); // old: passes on time
            awaitCount(orders, 6);

            assertEquals(3, quoter.quoteUpdates());
            assertEquals(1, quoter.suppressedUpdates());
        }
    }

    @Test
    void haltedSymbolCountsRejectedSides() throws Exception {
        HftRiskGate gate = openGate();
        try (HftOrderGateway gateway = new HftOrderGateway(1 << 10, gate, false)) {
            gateway.start();
            HftQuoter quoter = new HftQuoter(gateway, 16, HftQuoter.Config.of(100_000, 0.0002));
            gate.halt(0, true);
            quoter.onTick(0, 1.0850, 1e6, System.nanoTime());
            assertEquals(2, quoter.rejectedSides()); // both sides refused
            assertEquals(1, quoter.quoteUpdates());  // the attempt is still an update
        }
    }

    @Test
    void skewedQuotesBelowTheFirstBandNeverThrowOnTheConsumerThread() throws Exception {
        // A banded schedule whose first floor is 1.0: heavy long inventory
        // skews the bid to ~0.88, BELOW the band. Clamped rounding must keep
        // quoting (the risk gate is where bad prices die), because a throw
        // here would kill the bus consumer thread and every listener on it.
        HftRiskGate gate = openGate();
        try (HftOrderGateway gateway = new HftOrderGateway(1 << 10, gate, false)) {
            List<Captured> orders = capture(gateway);
            gateway.start();
            HftQuoter quoter = new HftQuoter(gateway, 16, HftQuoter.Config
                    .of(100_000, 0.0002)
                    .withSkewPerUnit(2e-7)
                    .withTickSchedule(com.quantfinlib.microstructure.TickSizeSchedule
                            .builder().addBand(1.0, 0.0001).build()));
            gate.onFill(0, Side.BUY, 1_000_000); // skew = −0.2
            quoter.onTick(0, 1.0850, 1e6, System.nanoTime());
            awaitCount(orders, 2);
            assertEquals(1, quoter.quoteUpdates());
            assertTrue(orders.get(0).price() < 1.0, "bid must sit below the band floor");
            assertTrue(orders.get(0).price() > 0.88 - 1e-9);
        }
    }

    @Test
    void perSymbolConfigServesAMixedBookFromOneQuoter() throws Exception {
        // EURUSD-style and USDJPY-style instruments on the same quoter:
        // per-symbol half-spreads and grids, one dispatch path.
        HftRiskGate gate = openGate();
        gate.setReferencePrice(1, 155.00);
        try (HftOrderGateway gateway = new HftOrderGateway(1 << 10, gate, false)) {
            List<Captured> orders = capture(gateway);
            gateway.start();
            HftQuoter quoter = new HftQuoter(gateway, 16,
                    HftQuoter.Config.of(100_000, 0.0002)   // default: FX-major style
                            .withTickSchedule(com.quantfinlib.microstructure.TickSizeSchedule
                                    .flat(0.00001)))
                    .configureSymbol(1, HftQuoter.Config.of(50_000, 0.02) // JPY-style
                            .withTickSchedule(com.quantfinlib.microstructure.TickSizeSchedule
                                    .flat(0.001)));

            quoter.onTick(0, 1.0850, 1e6, System.nanoTime());
            quoter.onTick(1, 155.00, 1e6, System.nanoTime());
            awaitCount(orders, 4);

            // Symbol 0: default config (2-pip half-spread, 100k size).
            assertEquals(1.0850 - 0.0002, orders.get(0).price(), 1e-9);
            assertEquals(100_000, orders.get(0).qty());
            // Symbol 1: overridden config (2-yen-pip half-spread, 50k size).
            assertEquals(155.00 - 0.02, orders.get(2).price(), 1e-9);
            assertEquals(155.00 + 0.02, orders.get(3).price(), 1e-9);
            assertEquals(50_000, orders.get(2).qty());
        }
    }

    @Test
    void configValidation() {
        assertThrows(IllegalArgumentException.class, () -> HftQuoter.Config.of(0, 0.0001));
        assertThrows(IllegalArgumentException.class, () -> HftQuoter.Config.of(100, 0));
        assertThrows(IllegalArgumentException.class,
                () -> HftQuoter.Config.of(100, 0.0001).withSkewPerUnit(-1));
    }

    @Test
    void withMinMoveIsPurelyMoveGated() throws Exception {
        // The pitfall this API exists for: withConflation(0, move) disables
        // conflation entirely (both gates must pass to suppress; nothing is
        // younger than 0ns). withMinMove suppresses on move alone.
        try (HftOrderGateway gateway = new HftOrderGateway(1 << 10, openGate(), false)) {
            gateway.start();
            HftQuoter moveGated = new HftQuoter(gateway, 16,
                    HftQuoter.Config.of(100_000, 0.0002).withMinMove(0.0005));
            long t0 = 1_000L;
            moveGated.onTick(0, 1.0850, 1e6, t0);                       // first: quotes
            moveGated.onTick(0, 1.08501, 1e6, t0 + 100_000_000_000L);   // tiny move, OLD: still suppressed
            moveGated.onTick(0, 1.0860, 1e6, t0 + 200_000_000_000L);    // big move: quotes
            assertEquals(2, moveGated.quoteUpdates());
            assertEquals(1, moveGated.suppressedUpdates());

            // Contrast: interval 0 never suppresses, whatever the move.
            HftQuoter disabled = new HftQuoter(gateway, 16,
                    HftQuoter.Config.of(100_000, 0.0002).withConflation(0, 0.0005));
            disabled.onTick(1, 1.0850, 1e6, t0);
            disabled.onTick(1, 1.08501, 1e6, t0 + 1);
            assertEquals(2, disabled.quoteUpdates());
            assertEquals(0, disabled.suppressedUpdates());
        }
    }

    @Test
    void quoterAndHedgerTickPathsAreAllocationFree() throws Exception {
        HftRiskGate gate = openGate();
        try (HftOrderGateway gateway = new HftOrderGateway(1 << 14, gate, false)) {
            // Blackhole venue: keeps the ring drained without test-side allocation.
            long[] blackhole = new long[1];
            gateway.addOrderListener((id, sym, side, qty, px, ts) -> blackhole[0] += qty);
            gateway.start();
            // Full quoting config: skew + grid snap + conflation all exercised.
            // Conflation interval far beyond the simulated clock: re-quotes are
            // driven purely by the min-move gate, so BOTH branches (quote and
            // suppress) run inside the measured loop.
            HftQuoter quoter = new HftQuoter(gateway, 16, HftQuoter.Config
                    .of(100_000, 0.0002)
                    .withSkewPerUnit(1e-10)
                    .withConflation(Long.MAX_VALUE / 4, 0.00005)
                    .withTickSchedule(com.quantfinlib.microstructure.TickSizeSchedule
                            .flat(0.00001)));
            AutoHedger hedger = new AutoHedger(gateway, 16, Long.MAX_VALUE / 8, 0);
            gate.onFill(0, Side.BUY, 1_000); // some inventory so skew is non-zero

            // JIT warmup before the measured window.
            for (int i = 0; i < 100_000; i++) {
                long ts = i * 10_000L;
                quoter.onTick(0, 1.0850 + (i % 100) * 1e-6, 1e6, ts);
                hedger.onTick(0, 1.0850, 1e6, ts);
            }
            var mx = (com.sun.management.ThreadMXBean)
                    java.lang.management.ManagementFactory.getThreadMXBean();
            long tid = Thread.currentThread().threadId();
            long before = mx.getThreadAllocatedBytes(tid);
            for (int i = 0; i < 500_000; i++) {
                long ts = 1_000_000_000L + i * 10_000L;
                // Mixes conflated (small move) and quoted (larger move) ticks.
                quoter.onTick(0, 1.0850 + (i % 100) * 1e-6, 1e6, ts);
                hedger.onTick(0, 1.0850, 1e6, ts);
            }
            long allocated = mx.getThreadAllocatedBytes(tid) - before;
            assertTrue(allocated < 100_000,
                    "tick paths allocated " + allocated + " bytes (quotes="
                            + quoter.quoteUpdates() + ", suppressed=" + quoter.suppressedUpdates()
                            + ", blackhole=" + blackhole[0] + ")");
            assertTrue(quoter.quoteUpdates() > 0 && quoter.suppressedUpdates() > 0,
                    "both the quoting and conflation branches must have run");
        }
    }

    // ------------------------------------------------------------------
    // AutoHedger
    // ------------------------------------------------------------------

    @Test
    void breachHedgesTheExcessBackToTheBand() throws Exception {
        HftRiskGate gate = openGate();
        try (HftOrderGateway gateway = new HftOrderGateway(1 << 10, gate, false)) {
            List<Captured> orders = capture(gateway);
            gateway.start();
            AutoHedger hedger = new AutoHedger(gateway, 16, 500_000, 0);

            // Inside the band: silence.
            gate.onFill(0, Side.BUY, 400_000);
            hedger.onTick(0, 1.0850, 1e6, System.nanoTime());
            assertEquals(0, hedger.hedgesSubmitted());

            // Breach long: sell exactly the excess over the band.
            gate.onFill(0, Side.BUY, 400_000); // position now +800k
            hedger.onTick(0, 1.0850, 1e6, System.nanoTime());
            awaitCount(orders, 1);
            assertEquals(Side.SELL, orders.get(0).side());
            assertEquals(300_000, orders.get(0).qty());
            assertEquals(1, hedger.hedgesSubmitted());

            // Short breach hedges with a buy.
            gate.onFill(0, Side.SELL, 1_600_000); // position now −800k
            hedger.onTick(0, 1.0850, 1e6, System.nanoTime());
            awaitCount(orders, 2);
            assertEquals(Side.BUY, orders.get(1).side());
            assertEquals(300_000, orders.get(1).qty());
        }
    }

    @Test
    void cooldownHoldsFireWhileAHedgeIsInFlight() throws Exception {
        HftRiskGate gate = openGate();
        try (HftOrderGateway gateway = new HftOrderGateway(1 << 10, gate, false)) {
            gateway.start();
            AutoHedger hedger = new AutoHedger(gateway, 16, 100_000, 1_000_000_000L);
            gate.onFill(0, Side.BUY, 500_000);
            long t0 = 5_000_000_000L;
            hedger.onTick(0, 1.0850, 1e6, t0);
            hedger.onTick(0, 1.0850, 1e6, t0 + 1000);  // inside cooldown: held
            assertEquals(1, hedger.hedgesSubmitted());
            hedger.onTick(0, 1.0850, 1e6, t0 + 2_000_000_000L); // cooldown expired
            assertEquals(2, hedger.hedgesSubmitted());
        }
    }

    @Test
    void firstHedgeFiresEvenWhenTheClockStartsAtZero() throws Exception {
        // Replay clocks start near 0 (and System.nanoTime may be negative):
        // a zero-initialized cooldown array would silently suppress startup
        // hedges. The sentinel guarantees the first breach always hedges.
        HftRiskGate gate = openGate();
        try (HftOrderGateway gateway = new HftOrderGateway(1 << 10, gate, false)) {
            gateway.start();
            AutoHedger hedger = new AutoHedger(gateway, 16, 100_000, 1_000_000_000L);
            gate.onFill(0, Side.BUY, 500_000);
            hedger.onTick(0, 1.0850, 1e6, 0L);          // t = 0: must fire
            assertEquals(1, hedger.hedgesSubmitted());
            AutoHedger negative = new AutoHedger(gateway, 16, 100_000, 1_000_000_000L);
            gate.onFill(1, Side.BUY, 500_000);
            gate.setReferencePrice(1, 1.0850);
            negative.onTick(1, 1.0850, 1e6, -5_000_000_000L); // negative nanoTime origin
            assertEquals(1, negative.hedgesSubmitted());
        }
    }

    @Test
    void refusedHedgesAreCountedForMonitoring() throws Exception {
        HftRiskGate gate = openGate();
        try (HftOrderGateway gateway = new HftOrderGateway(1 << 10, gate, false)) {
            gateway.start();
            AutoHedger hedger = new AutoHedger(gateway, 16, 100_000, 0);
            gate.onFill(0, Side.BUY, 500_000);
            gate.halt(0, true); // risk gate refuses everything now
            hedger.onTick(0, 1.0850, 1e6, System.nanoTime());
            assertEquals(0, hedger.hedgesSubmitted());
            assertEquals(1, hedger.hedgesRejected());
            assertThrows(IllegalArgumentException.class,
                    () -> new AutoHedger(gateway, 16, 0, 0));
        }
    }
}
