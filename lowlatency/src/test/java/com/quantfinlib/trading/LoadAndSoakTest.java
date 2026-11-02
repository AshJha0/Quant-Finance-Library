package com.quantfinlib.trading;

import com.quantfinlib.marketdata.HftMarketDataBus;
import com.quantfinlib.orderbook.HftOrderBook;
import com.quantfinlib.core.Side;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Performance floors, soak, and overload behavior — the load-test layer of
 * the suite. Three distinct jobs:
 *
 * <ul>
 *   <li><b>Throughput floors</b>: regression tripwires, not benchmarks.
 *       The floors sit ~20× below desktop-measured numbers (bus 9–12M/s,
 *       gateway 21M/s, book 12M/s), so slow shared CI runners never flake,
 *       while an accidental lock, allocation or O(n) slip on a hot path
 *       (the mistakes that cost an order of magnitude) fails the build.
 *       Real numbers come from the committed benchmarks, not from here.</li>
 *   <li><b>Soak</b> — sustained mixed load with a heap-stability assertion:
 *       steady-state hot paths must not grow the heap (leaks and slow
 *       allocation drips surface here, complementing the per-thread
 *       allocation-counter proofs).</li>
 *   <li><b>Overload</b> — intentionally undersized rings under a burst:
 *       the contract is degrade-and-count (publish returns false, submits
 *       return 0, counters tick), never throw, and full recovery once the
 *       burst passes.</li>
 * </ul>
 */
@Tag("load")
class LoadAndSoakTest {

    private static HftRiskGate openGate() {
        HftRiskGate gate = new HftRiskGate(16)
                .maxOrderQuantity(10_000_000)
                .maxOrderNotional(1e15)
                .maxPositionQuantity(Long.MAX_VALUE / 4);
        gate.setReferencePrice(0, 1.0850);
        return gate;
    }

    // ------------------------------------------------------------------
    // Throughput floors (order-of-magnitude regression tripwires)
    // ------------------------------------------------------------------

    @Test
    void busSustainsTheThroughputFloor() throws Exception {
        try (HftMarketDataBus bus = new HftMarketDataBus(1 << 14, 4, false)) {
            long[] blackhole = {0};
            int id = bus.registerSymbol("X");
            bus.subscribe(id, (s, p, sz, t) -> blackhole[0]++);
            bus.start();
            int n = 2_000_000;
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                while (!bus.publish(id, 1.0850, 1e6, i)) {
                    Thread.onSpinWait();
                }
            }
            long busDeadline = System.nanoTime() + 60_000_000_000L;
            while (bus.processedCount() < n && System.nanoTime() < busDeadline) {
                Thread.onSpinWait();
            }
            assertEquals(n, bus.processedCount());
            double perSec = n / ((System.nanoTime() - t0) / 1e9);
            // Desktop: 9-12M/s. Floor: 500k/s — a 20x regression trips it.
            assertTrue(perSec > 500_000, String.format("bus %.0f ticks/s", perSec));
        }
    }

    @Test
    void orderPathSustainsTheThroughputFloor() throws Exception {
        try (HftOrderGateway gateway = new HftOrderGateway(1 << 14, openGate(), false)) {
            // The venue fills every order, so order-state slots recycle.
            gateway.addOrderListener(new LoopbackVenue(gateway, LoopbackVenue.FILL_ALL, 1 << 14));
            gateway.start();
            int n = 2_000_000;
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                while (gateway.submit(0, (i & 1) == 0 ? Side.BUY : Side.SELL,
                        1_000, 1.0850, i) <= 0) {
                    Thread.onSpinWait();
                }
            }
            long gwDeadline = System.nanoTime() + 60_000_000_000L;
            while (gateway.deliveredCount() < n && System.nanoTime() < gwDeadline) {
                Thread.onSpinWait();
            }
            assertEquals(n, gateway.deliveredCount());
            double perSec = n / ((System.nanoTime() - t0) / 1e9);
            // Desktop: 21M/s. Floor: 1M/s.
            assertTrue(perSec > 1_000_000, String.format("gateway %.0f orders/s", perSec));
        }
    }

    @Test
    void matchingEngineSustainsTheThroughputFloor() {
        HftOrderBook book = new HftOrderBook(90_000, 110_000, 1 << 16);
        book.tradeSink((m, t, tick, q, ts) -> { /* blackhole */ });
        SplittableRandom rnd = new SplittableRandom(42);
        int n = 1_000_000;
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            int tick = 99_900 + rnd.nextInt(200);
            if ((i % 3) != 0) {
                book.submitLimit((i & 1) == 0 ? Side.BUY : Side.SELL,
                        (i & 1) == 0 ? tick - 60 : tick + 60, 1 + rnd.nextInt(50), i);
            } else {
                book.submitMarket(rnd.nextBoolean() ? Side.BUY : Side.SELL,
                        1 + rnd.nextInt(120), i);
            }
        }
        double perSec = n / ((System.nanoTime() - t0) / 1e9);
        // Desktop: 12M/s. Floor: 500k/s.
        assertTrue(perSec > 500_000, String.format("book %.0f ops/s", perSec));
        assertTrue(book.tradeCount() > 0);
    }

    @Test
    void fullPipelineSustainsTheThroughputFloorUnderLoad() throws Exception {
        // The composite path: bus -> quoter -> gate -> order ring -> venue,
        // 1M ticks. The quoter keeps one working order per side and
        // requotes by cancel/replace once the venue has answered; updates
        // that arrive while a side waits for the venue are deferred, not
        // stacked. Every message the gateway accepted must reach the venue.
        HftRiskGate gate = openGate();
        try (HftMarketDataBus bus = new HftMarketDataBus(1 << 14, 4, false);
             HftOrderGateway gateway = new HftOrderGateway(1 << 16, gate, true)) {
            gateway.addOrderListener(new LoopbackVenue(gateway));
            HftQuoter quoter = new HftQuoter(gateway, 4,
                    HftQuoter.Config.of(100_000, 0.0002));
            int id = bus.registerSymbol("X");
            bus.subscribe(id, quoter);
            gateway.start();
            bus.start();
            int n = 1_000_000;
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                while (!bus.publish(id, 1.0850 + (i % 100) * 1e-6, 1e6, i)) {
                    Thread.onSpinWait();
                }
            }
            long deadline = System.nanoTime() + 60_000_000_000L;
            while (bus.processedCount() < n && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            double perSec = n / ((System.nanoTime() - t0) / 1e9);
            assertEquals(n, bus.processedCount());
            // Floor 100k ticks/s: a tripwire for a broken consumer, not a benchmark.
            assertTrue(perSec > 100_000, String.format("pipeline %.0f ticks/s", perSec));
            long sent = gateway.submittedCount() + gateway.replaceRequestCount()
                    + gateway.cancelRequestCount();
            while (gateway.deliveredCount() < sent && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(sent, gateway.deliveredCount(), "conservation: nothing lost in the ring");
            assertEquals(n, quoter.quoteUpdates());
            assertTrue(quoter.replaces() > 0);
            // Never more than one working order per side.
            assertTrue(gate.openQuantity(id, Side.BUY) <= 100_000);
            assertTrue(gate.openQuantity(id, Side.SELL) <= 100_000);
            assertTrue(gateway.isHealthy());
        }
    }

    // ------------------------------------------------------------------
    // Overload: degrade-and-count, never throw, full recovery
    // ------------------------------------------------------------------

    @Test
    void overloadDegradesGracefullyAndRecovers() throws Exception {
        // Intentionally tiny rings under a blast: the contract is that
        // nothing throws, drops are visible in counters, and the system
        // serves normal traffic again the moment the burst ends.
        HftRiskGate gate = openGate();
        try (HftMarketDataBus bus = new HftMarketDataBus(1 << 6, 4, false);
             HftOrderGateway gateway = new HftOrderGateway(1 << 6, gate, false)) {
            gateway.addOrderListener(new LoopbackVenue(gateway));
            HftQuoter quoter = new HftQuoter(gateway, 4,
                    HftQuoter.Config.of(100_000, 0.0002));
            int id = bus.registerSymbol("X");
            bus.subscribe(id, quoter);
            gateway.start();
            bus.start();

            // Blast without pacing: many publishes must fail (ring 64 deep).
            long refused = 0;
            for (int i = 0; i < 100_000; i++) {
                if (!bus.publish(id, 1.0850, 1e6, i)) {
                    refused++;
                }
            }
            assertTrue(refused > 0, "the burst must actually overload the ring");
            // Some quoter sides may have hit a full order ring: counted.
            long droppedSides = quoter.rejectedSides();
            assertTrue(droppedSides >= 0); // never threw to get here

            // Let the consumer finish draining blast leftovers (count
            // stabilizes) before snapshotting — asserting exact counts while
            // the drain races the snapshot flaked this test once.
            long settled = bus.processedCount();
            for (int i = 0; i < 200; i++) {
                Thread.sleep(5);
                long now = bus.processedCount();
                if (now == settled) {
                    break;
                }
                settled = now;
            }
            // Recovery: paced traffic flows loss-free immediately after.
            long processedBefore = bus.processedCount();
            long deliveredBefore = gateway.deliveredCount();
            for (int i = 0; i < 1_000; i++) {
                while (!bus.publish(id, 1.0850 + i * 1e-6, 1e6, 200_000 + i)) {
                    Thread.onSpinWait();
                }
            }
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (bus.processedCount() < processedBefore + 1_000
                    && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertTrue(bus.processedCount() >= processedBefore + 1_000,
                    "the bus must fully recover after the burst");
            // The venue thread parks between drains: give delivery the same
            // bounded patience before asserting flow.
            while (gateway.deliveredCount() <= deliveredBefore
                    && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertTrue(gateway.deliveredCount() > deliveredBefore,
                    "post-burst traffic must flow");
        }
    }
}
