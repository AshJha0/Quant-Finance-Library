package com.quantfinlib.marketdata;

import com.quantfinlib.core.Bar;
import com.quantfinlib.risk.Portfolio;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketDataTest {

    @Test
    void ringBufferPreservesFifoOrder() {
        RingBuffer<Integer> rb = new RingBuffer<>(8);
        for (int i = 0; i < 5; i++) {
            assertTrue(rb.offer(i));
        }
        for (int i = 0; i < 5; i++) {
            assertEquals(i, rb.poll());
        }
        assertNull(rb.poll());
    }

    @Test
    void ringBufferRejectsWhenFull() {
        RingBuffer<Integer> rb = new RingBuffer<>(4);
        int accepted = 0;
        while (rb.offer(accepted)) {
            accepted++;
        }
        assertEquals(rb.capacity(), accepted);
    }

    @Test
    void processorDispatchesToListenersAndUpdatesPrices() throws Exception {
        try (MarketDataProcessor mdp = new MarketDataProcessor(1024)) {
            CountDownLatch latch = new CountDownLatch(3);
            AtomicInteger symbolEvents = new AtomicInteger();
            mdp.subscribe("EURUSD", e -> symbolEvents.incrementAndGet());
            mdp.subscribeAll(e -> latch.countDown());
            mdp.start();

            mdp.publish(new MarketDataEvent("EURUSD", 1.0850, 1_000_000, System.nanoTime()));
            mdp.publish(new MarketDataEvent("EURUSD", 1.0855, 500_000, System.nanoTime()));
            mdp.publish(new MarketDataEvent("GBPUSD", 1.2701, 250_000, System.nanoTime()));

            assertTrue(latch.await(5, TimeUnit.SECONDS), "events not delivered");
            assertEquals(2, symbolEvents.get());
            assertEquals(1.0855, mdp.latestPrice("EURUSD"), 1e-12);
            assertEquals(1.2701, mdp.latestPrice("GBPUSD"), 1e-12);
            // processedCount increments after listener dispatch: wait for it.
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (mdp.processedCount() < 3 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(3, mdp.processedCount());
            assertEquals(0, mdp.droppedCount());
        }
    }

    @Test
    void monitoredPortfolioStaysMarkedToMarket() throws Exception {
        Portfolio p = new Portfolio().addPosition("AAPL", 10, 100);
        try (MarketDataProcessor mdp = new MarketDataProcessor()) {
            mdp.monitor(p);
            CountDownLatch latch = new CountDownLatch(1);
            mdp.subscribeAll(e -> latch.countDown());
            mdp.start();
            mdp.publish(new MarketDataEvent("AAPL", 110, 100, System.nanoTime()));
            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertEquals(1_100, p.totalValue(), 1e-9);
        }
    }

    @Test
    void historicalStoreRoundTrips() {
        HistoricalDataStore store = new HistoricalDataStore();
        store.addBar("SPY", new Bar(1, 100, 102, 99, 101, 5_000));
        store.addBar("SPY", new Bar(2, 101, 103, 100, 102, 6_000));
        assertEquals(2, store.barCount("SPY"));
        assertEquals(102, store.series("SPY").lastClose(), 1e-12);
        assertTrue(store.contains("SPY"));
    }
}
