package com.quantfinlib.trading;

import com.quantfinlib.orderbook.Side;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Horizontal scaling machinery: symbol routing across shared-nothing
 * shards (including multi-shard duplication for cross co-location), the
 * gate-wide kill switch, and the cross-shard GlobalRiskAggregator breaker
 * with hysteretic recovery.
 */
class ShardedTradingTest {

    private static HftRiskGate openGate(int ignored) {
        return new HftRiskGate(64)
                .maxOrderQuantity(10_000_000)
                .maxOrderNotional(1e15)
                .maxPositionQuantity(Long.MAX_VALUE / 4);
    }

    private static void awaitTrue(java.util.function.BooleanSupplier c) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!c.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met in time");
            }
            Thread.sleep(1);
        }
    }

    @Test
    void routesSymbolsToTheirShardsAndDuplicatesCrossLegs() throws Exception {
        try (ShardedTradingEngine engine = new ShardedTradingEngine(
                2, 1 << 12, 1 << 12, 64, false, ShardedTradingTest::openGate)) {
            int eurusd = engine.registerSymbol("EURUSD", 0);
            int gbpusd = engine.registerSymbol("GBPUSD", 1);
            // The cross-leg pattern: USDJPY duplicated into BOTH shards.
            int usdjpy = engine.registerSymbol("USDJPY", 0, 1);

            AtomicLong shard0Ticks = new AtomicLong();
            AtomicLong shard1Ticks = new AtomicLong();
            engine.bus(0).subscribeAll((id, p, s, t) -> shard0Ticks.incrementAndGet());
            engine.bus(1).subscribeAll((id, p, s, t) -> shard1Ticks.incrementAndGet());
            engine.start();

            assertTrue(engine.publish(eurusd, 1.0850, 1e6, 1));
            assertTrue(engine.publish(gbpusd, 1.2700, 1e6, 2));
            assertTrue(engine.publish(usdjpy, 155.00, 1e6, 3)); // fans to both
            awaitTrue(() -> engine.processedCount() == 4);

            assertEquals(2, shard0Ticks.get()); // EURUSD + duplicated USDJPY
            assertEquals(2, shard1Ticks.get()); // GBPUSD + duplicated USDJPY
            // Per-shard dense ids resolve for subscription wiring.
            assertEquals(engine.bus(0).symbolId("USDJPY"), engine.localId(usdjpy, 0));
            assertEquals(engine.bus(1).symbolId("USDJPY"), engine.localId(usdjpy, 1));
            assertThrows(IllegalArgumentException.class, () -> engine.localId(eurusd, 1));
            assertThrows(IllegalStateException.class, () -> engine.registerSymbol("LATE", 0));
        }
    }

    @Test
    void shardsTradeIndependentlyAndCountersAggregate() throws Exception {
        try (ShardedTradingEngine engine = new ShardedTradingEngine(
                2, 1 << 12, 1 << 12, 64, false, ShardedTradingTest::openGate)) {
            int a = engine.registerSymbol("A", 0);
            int b = engine.registerSymbol("B", 1);
            // A quoter per shard, wired exactly like on a standalone bus.
            for (int s = 0; s < 2; s++) {
                HftQuoter quoter = new HftQuoter(engine.gateway(s), 64,
                        HftQuoter.Config.of(100_000, 0.0002));
                engine.bus(s).subscribeAll(quoter);
            }
            engine.start();
            engine.publish(a, 1.0850, 1e6, 1);
            engine.publish(b, 1.2700, 1e6, 2);
            awaitTrue(() -> engine.deliveredCount() == 4); // 2 sides × 2 shards
            assertEquals(2, engine.processedCount());
            assertEquals(2, engine.shardCount());
        }
    }

    @Test
    void killSwitchRejectsEverythingUntilCleared() {
        HftRiskGate gate = openGate(0);
        assertEquals(HftRiskGate.OK, gate.check(0, Side.BUY, 100, 1.0));
        gate.kill(true);
        assertTrue(gate.isKilled());
        assertEquals(HftRiskGate.REJECT_KILLED, gate.check(0, Side.BUY, 100, 1.0));
        assertEquals(HftRiskGate.REJECT_KILLED, gate.check(1, Side.SELL, 1, 1.0));
        assertEquals("KILLED", HftRiskGate.reasonName(HftRiskGate.REJECT_KILLED));
        assertEquals(2, gate.rejectionCount(HftRiskGate.REJECT_KILLED));
        gate.kill(false);
        assertEquals(HftRiskGate.OK, gate.check(0, Side.BUY, 100, 1.0));
    }

    @Test
    void aggregatorTripsAcrossShardsAndRecoversWithHysteresis() throws Exception {
        HftRiskGate gate0 = openGate(0);
        HftRiskGate gate1 = openGate(1);
        gate0.setReferencePrice(0, 100);
        gate1.setReferencePrice(0, 200);
        // Cap 100k gross; resume below 50k.
        try (GlobalRiskAggregator aggregator = new GlobalRiskAggregator(
                java.util.List.of(gate0, gate1), 100_000, 0.5, 100_000)) {

            // 60k on shard 0 + 30k on shard 1 = 90k gross: under the cap.
            gate0.onFill(0, Side.BUY, 600);
            gate1.onFill(0, Side.SELL, 150);   // |−150| × 200 = 30k
            Thread.sleep(50);
            assertFalse(aggregator.isTripped());
            assertEquals(90_000, aggregator.grossNotional(), 1e-9);

            // +20k pushes gross to 110k: the breaker must trip EVERY gate.
            gate0.onFill(0, Side.BUY, 200);
            awaitTrue(aggregator::isTripped);
            assertEquals(HftRiskGate.REJECT_KILLED, gate0.check(0, Side.BUY, 1, 100));
            assertEquals(HftRiskGate.REJECT_KILLED, gate1.check(0, Side.BUY, 1, 200));

            // Reduce to 80k: still tripped (hysteresis floor is 50k)...
            gate0.onFill(0, Side.SELL, 300);
            Thread.sleep(50);
            assertTrue(aggregator.isTripped());
            // ...flatten to 30k: trading resumes on every shard.
            gate0.onFill(0, Side.SELL, 500);
            awaitTrue(() -> !aggregator.isTripped());
            assertEquals(HftRiskGate.OK, gate0.check(0, Side.BUY, 1, 100));
            assertEquals(1, aggregator.tripCount());
        }
        assertThrows(IllegalArgumentException.class, () -> new GlobalRiskAggregator(
                java.util.List.of(), 100_000, 0.5, 100_000));
    }
}
