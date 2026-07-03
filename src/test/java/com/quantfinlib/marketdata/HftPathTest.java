package com.quantfinlib.marketdata;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HftPathTest {

    @Test
    void tickRingPreservesFifoOrder() {
        TickRingBuffer ring = new TickRingBuffer(16);
        for (int i = 0; i < 10; i++) {
            assertTrue(ring.publish(i, i * 1.5, i, i * 100L));
        }
        List<Integer> ids = new ArrayList<>();
        List<Double> prices = new ArrayList<>();
        int n = ring.drainTo((id, price, size, ts) -> {
            ids.add(id);
            prices.add(price);
        }, 100);
        assertEquals(10, n);
        for (int i = 0; i < 10; i++) {
            assertEquals(i, ids.get(i));
            assertEquals(i * 1.5, prices.get(i), 0.0);
        }
        assertTrue(ring.isEmpty());
    }

    @Test
    void tickRingRejectsWhenFullThenRecovers() {
        TickRingBuffer ring = new TickRingBuffer(8);
        int accepted = 0;
        while (ring.publish(1, 1.0, 1, 0)) {
            accepted++;
        }
        assertEquals(ring.capacity(), accepted);
        // Drain one; a slot frees up.
        assertEquals(1, ring.drainTo((id, p, s, t) -> { }, 1));
        assertTrue(ring.publish(2, 2.0, 1, 0));
    }

    @Test
    void tickRingDeliversAcrossThreadsInOrder() throws Exception {
        TickRingBuffer ring = new TickRingBuffer(1024);
        int total = 200_000;
        Thread producer = new Thread(() -> {
            for (int i = 0; i < total; i++) {
                while (!ring.publish(0, i, 1, i)) {
                    Thread.onSpinWait();
                }
            }
        });
        producer.start();

        long[] received = {0};
        boolean[] ordered = {true};
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (received[0] < total && System.nanoTime() < deadline) {
            ring.drainTo((id, price, size, ts) -> {
                if ((long) price != received[0]) {
                    ordered[0] = false;
                }
                received[0]++;
            }, 4096);
        }
        producer.join(5_000);
        assertEquals(total, received[0]);
        assertTrue(ordered[0], "ticks arrived out of order");
    }

    @Test
    void symbolRegistryInternsStableIds() {
        SymbolRegistry reg = new SymbolRegistry();
        int a = reg.register("EURUSD");
        int b = reg.register("GBPUSD");
        assertEquals(a, reg.register("EURUSD"));   // idempotent
        assertEquals(a, reg.id("EURUSD"));
        assertEquals("GBPUSD", reg.symbol(b));
        assertEquals(2, reg.size());
        assertThrows(IllegalArgumentException.class, () -> reg.id("UNKNOWN"));
    }

    @Test
    void busDispatchesBySymbolIdAndTracksLatestPrice() throws Exception {
        try (HftMarketDataBus bus = new HftMarketDataBus(1024, 8, false)) {
            int eur = bus.registerSymbol("EURUSD");
            int gbp = bus.registerSymbol("GBPUSD");
            AtomicInteger eurTicks = new AtomicInteger();
            AtomicLong global = new AtomicLong();
            bus.subscribe(eur, (id, p, s, t) -> eurTicks.incrementAndGet());
            bus.subscribeAll((id, p, s, t) -> global.incrementAndGet());
            assertTrue(Double.isNaN(bus.latestPrice(eur)));
            bus.start();

            bus.publish(eur, 1.0850, 1e6, System.nanoTime());
            bus.publish(eur, 1.0852, 1e6, System.nanoTime());
            bus.publish(gbp, 1.2700, 1e6, System.nanoTime());

            long deadline = System.nanoTime() + 5_000_000_000L;
            while (bus.processedCount() < 3 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(3, bus.processedCount());
            assertEquals(2, eurTicks.get());
            assertEquals(3, global.get());
            assertEquals(1.0852, bus.latestPrice("EURUSD"), 0.0);
            assertEquals(1.2700, bus.latestPrice(gbp), 0.0);
            assertEquals(0, bus.ringFullCount());
        }
    }

    @Test
    void busStopDrainsPendingTicks() {
        HftMarketDataBus bus = new HftMarketDataBus(1024, 4, false);
        int id = bus.registerSymbol("X");
        AtomicInteger seen = new AtomicInteger();
        bus.subscribe(id, (sid, p, s, t) -> seen.incrementAndGet());
        bus.start();
        for (int i = 0; i < 500; i++) {
            assertTrue(bus.publish(id, i, 1, i));
        }
        bus.stop();   // must drain before returning
        assertEquals(500, seen.get());
        assertFalse(Double.isNaN(bus.latestPrice(id)));
    }
}
