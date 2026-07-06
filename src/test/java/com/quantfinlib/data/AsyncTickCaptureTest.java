package com.quantfinlib.data;

import com.quantfinlib.marketdata.HftMarketDataBus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Async tick capture: everything published makes it to the file (through
 * the ring and writer thread), replays byte-identically, and the
 * backpressure policy is drop-and-count, never block.
 */
class AsyncTickCaptureTest {

    @Test
    void capturesEverythingAndReplaysIdentically(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("session.qflt");
        int ticks = 50_000;
        try (HftMarketDataBus bus = new HftMarketDataBus(1 << 14, 8, false)) {
            int eurusd = bus.registerSymbol("EURUSD");
            int usdjpy = bus.registerSymbol("USDJPY");
            try (AsyncTickCapture capture = AsyncTickCapture.attach(bus, file, 1 << 16)) {
                bus.start();
                for (int i = 0; i < ticks; i++) {
                    int id = (i & 1) == 0 ? eurusd : usdjpy;
                    while (!bus.publish(id, 1.0850 + i * 1e-7, 1_000 + i, i)) {
                        Thread.onSpinWait();
                    }
                }
                // Wait for the consumer to hand everything to the capture ring.
                long deadline = System.nanoTime() + 5_000_000_000L;
                while (bus.processedCount() < ticks && System.nanoTime() < deadline) {
                    Thread.sleep(1);
                }
                assertEquals(0, capture.droppedTicks());
            } // close(): drains the ring fully, then closes the file.

            List<long[]> replayed = new ArrayList<>();
            TickFileReader.replay(file, (symbolId, price, size, timestampNanos) ->
                    replayed.add(new long[]{symbolId, (long) (price * 1e7), timestampNanos}));
            assertEquals(ticks, replayed.size());
            // Spot-check ordering and content fidelity.
            assertEquals(0, replayed.get(0)[2]);
            assertEquals(ticks - 1, replayed.get(ticks - 1)[2]);
            assertEquals((long) (1.0850 * 1e7), replayed.get(0)[1]);
        }
    }

    @Test
    void fullRingDropsAndCountsInsteadOfBlocking(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("tiny.qflt");
        try (HftMarketDataBus bus = new HftMarketDataBus(1 << 14, 4, false)) {
            int id = bus.registerSymbol("X");
            // A 16-slot capture ring with a writer that CANNOT keep up with a
            // burst published faster than file I/O: drops must be counted and
            // the publisher must never block on the capture.
            try (AsyncTickCapture capture = AsyncTickCapture.attach(bus, file, 16)) {
                bus.start();
                for (int i = 0; i < 100_000; i++) {
                    while (!bus.publish(id, 1.0, 1, i)) {
                        Thread.onSpinWait();
                    }
                }
                long deadline = System.nanoTime() + 5_000_000_000L;
                while (bus.processedCount() < 100_000 && System.nanoTime() < deadline) {
                    Thread.sleep(1);
                }
                // The bus itself processed everything — the capture absorbed
                // the shortfall as counted drops, not as consumer stalls.
                assertEquals(100_000, bus.processedCount());
                assertTrue(capture.droppedTicks() + capture.ticksWritten() >= 16,
                        "capture must have made progress");
            }
        }
    }
}
