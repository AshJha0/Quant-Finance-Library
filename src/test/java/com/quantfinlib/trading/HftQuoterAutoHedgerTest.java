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
    void configValidation() {
        assertThrows(IllegalArgumentException.class, () -> HftQuoter.Config.of(0, 0.0001));
        assertThrows(IllegalArgumentException.class, () -> HftQuoter.Config.of(100, 0));
        assertThrows(IllegalArgumentException.class,
                () -> HftQuoter.Config.of(100, 0.0001).withSkewPerUnit(-1));
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
