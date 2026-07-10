package com.quantfinlib.trading;

import com.quantfinlib.marketdata.HftMarketDataBus;
import com.quantfinlib.orderbook.HftOrderBook;
import com.quantfinlib.orderbook.Side;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Performance floors, soak, and overload behavior — the load-test layer of
 * the suite. Three distinct jobs:
 *
 * <ul>
 *   <li><b>Throughput floors</b> — regression tripwires, NOT benchmarks:
 *       the floors sit ~20× below desktop-measured numbers (bus 9–12M/s,
 *       gateway 21M/s, book 12M/s), so slow shared CI runners never flake,
 *       while an accidental lock, allocation or O(n) slip on a hot path —
 *       the mistakes that cost an order of magnitude — fails the build.
 *       Real numbers come from the committed benchmarks, not from here.</li>
 *   <li><b>Soak</b> — sustained mixed load with a heap-stability assertion:
 *       steady-state hot paths must not grow the heap (leaks and slow
 *       allocation drips surface here, complementing the per-thread
 *       allocation-counter proofs).</li>
 *   <li><b>Overload</b> — deliberately undersized rings under a burst:
 *       the contract is degrade-and-count (publish returns false, submits
 *       return 0, counters tick), never throw, and full recovery once the
 *       burst passes.</li>
 * </ul>
 */
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
            long[] blackhole = {0};
            gateway.addOrderListener((id, s, side, q, p, t) -> blackhole[0]++);
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
        // 1M ticks quoted two-sided at full speed. Drops are budgeted, not
        // forbidden: even a busy-spinning venue thread gets preempted for
        // milliseconds by a desktop OS, and a ~10M orders/s burst overruns
        // any ring in that window — zero-drop under saturation is what
        // Tier-3 core pinning buys (see ULTRA_LOW_LATENCY.md), not something
        // an unpinned CI box can promise. Budget: 0.5% of sides.
        HftRiskGate gate = openGate();
        try (HftMarketDataBus bus = new HftMarketDataBus(1 << 14, 4, false);
             HftOrderGateway gateway = new HftOrderGateway(1 << 16, gate, true)) {
            long[] blackhole = {0};
            gateway.addOrderListener((id, s, side, q, p, t) -> blackhole[0]++);
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
            // Drain with a DEADLINE and account for any rejected sides: an
            // unconditional wait for 2n once hung this suite forever when a
            // transiently full order ring dropped a single quote side.
            long deadline = System.nanoTime() + 60_000_000_000L;
            while (gateway.deliveredCount() + quoter.rejectedSides() < 2L * n
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            double perSec = n / ((System.nanoTime() - t0) / 1e9);
            // Desktop: ~1.7-4M ticks/s quoting. Floor: 100k/s.
            assertTrue(perSec > 100_000, String.format("pipeline %.0f ticks/s", perSec));
            long droppedSides = quoter.rejectedSides();
            // Desktop budget: 0.5% of sides. Shared CI runners (2 vCPUs,
            // noisy neighbors) preempt the venue thread for WHOLE
            // timeslices — 2.5–4.3% drops measured on GitHub runners at
            // v1.12.0 — so CI gets 8%: ~2x the worst observation, still a
            // tripwire (a broken consumer drops 50-100%), never a
            // pass-anything. The conservation assert below is the real
            // correctness check and stays exact in both environments.
            long budget = System.getenv("CI") != null ? 2L * n * 8 / 100 : 2L * n / 200;
            assertTrue(droppedSides <= budget, "dropped " + droppedSides
                    + " sides — beyond OS-preemption budget " + budget);
            assertEquals(2L * n - droppedSides, blackhole[0]);
        }
    }

    // ------------------------------------------------------------------
    // Soak: sustained load must not grow the heap
    // ------------------------------------------------------------------

    @Test
    void sustainedMixedLoadKeepsTheHeapFlat() {
        // Warm everything (JIT + lazily-initialized JDK internals), settle
        // the heap, then run 5M mixed operations and demand the heap ends
        // where it started — the leak/drip detector for the hot paths.
        HftOrderBook book = new HftOrderBook(90_000, 110_000, 1 << 14);
        book.tradeSink((m, t, tick, q, ts) -> { /* blackhole */ });
        SplittableRandom rnd = new SplittableRandom(7);
        long[] ring = new long[4096];
        int ringSize = 0;
        for (int i = 0; i < 500_000; i++) {           // warmup
            ringSize = churn(book, rnd, ring, ringSize, i);
        }
        Runtime rt = Runtime.getRuntime();
        System.gc();
        sleepQuiet();
        long before = rt.totalMemory() - rt.freeMemory();
        for (int i = 0; i < 5_000_000; i++) {          // soak
            ringSize = churn(book, rnd, ring, ringSize, i);
        }
        System.gc();
        sleepQuiet();
        long after = rt.totalMemory() - rt.freeMemory();
        long growth = after - before;
        // Allow 10MB of noise (GC bookkeeping, JIT); a real drip at even
        // 4 bytes/op would show as ~20MB here.
        assertTrue(growth < 10_000_000,
                "heap grew " + growth + " bytes over 5M sustained operations");
        assertTrue(book.tradeCount() > 100_000, "soak must exercise matching");
    }

    private static int churn(HftOrderBook book, SplittableRandom rnd, long[] ring,
                             int ringSize, int op) {
        if (rnd.nextInt(10) < 7 || ringSize == 0) {
            long id = book.submitLimit(rnd.nextBoolean() ? Side.BUY : Side.SELL,
                    99_000 + rnd.nextInt(2_000), 1 + rnd.nextInt(50), op);
            if (id > 0 && book.openQuantity(id) > 0 && ringSize < ring.length) {
                ring[ringSize++] = id;
            }
        } else {
            int pick = rnd.nextInt(ringSize);
            book.cancel(ring[pick]);
            ring[pick] = ring[--ringSize];
        }
        return ringSize;
    }

    private static void sleepQuiet() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------
    // Overload: degrade-and-count, never throw, full recovery
    // ------------------------------------------------------------------

    @Test
    void overloadDegradesGracefullyAndRecovers() throws Exception {
        // Deliberately tiny rings under a blast: the contract is that
        // nothing throws, drops are visible in counters, and the system
        // serves normal traffic again the moment the burst ends.
        HftRiskGate gate = openGate();
        try (HftMarketDataBus bus = new HftMarketDataBus(1 << 6, 4, false);
             HftOrderGateway gateway = new HftOrderGateway(1 << 6, gate, false)) {
            long[] delivered = {0};
            gateway.addOrderListener((id, s, side, q, p, t) -> delivered[0]++);
            HftQuoter quoter = new HftQuoter(gateway, 4,
                    HftQuoter.Config.of(100_000, 0.0002));
            int id = bus.registerSymbol("X");
            bus.subscribe(id, quoter);
            gateway.start();
            bus.start();

            // Blast without pacing: many publishes MUST fail (ring 64 deep).
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
