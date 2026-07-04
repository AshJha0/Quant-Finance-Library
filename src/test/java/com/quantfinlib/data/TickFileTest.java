package com.quantfinlib.data;

import com.quantfinlib.marketdata.HftMarketDataBus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TickFileTest {

    private record Tick(int symbolId, double price, double size, long ts) {
    }

    private static final class Collector implements TickFileReader.ReplayHandler {
        final Map<Integer, String> symbols = new HashMap<>();
        final List<Tick> ticks = new ArrayList<>();

        @Override
        public void onSymbol(int symbolId, String symbol) {
            symbols.put(symbolId, symbol);
        }

        @Override
        public void onTick(int symbolId, double price, double size, long timestampNanos) {
            ticks.add(new Tick(symbolId, price, size, timestampNanos));
        }
    }

    @Test
    void roundTripsTicksAndMidStreamSymbolDefinitions(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("session.qflt");
        try (TickFileWriter w = new TickFileWriter(file)) {
            w.defineSymbol(0, "EURUSD");
            w.write(0, 1.0850, 1_000_000, 100);
            w.write(0, 1.0851, 500_000, 200);
            w.defineSymbol(1, "GBPUSD");          // defined mid-capture
            w.write(1, 1.2700, 250_000, 300);
            w.write(0, 1.0852, 750_000, 400);
            assertEquals(4, w.tickCount());
        }

        Collector c = new Collector();
        long ticks = TickFileReader.replay(file, c);

        assertEquals(4, ticks);
        assertEquals("EURUSD", c.symbols.get(0));
        assertEquals("GBPUSD", c.symbols.get(1));
        assertEquals(new Tick(0, 1.0850, 1_000_000, 100), c.ticks.getFirst());
        assertEquals(new Tick(1, 1.2700, 250_000, 300), c.ticks.get(2));
        assertEquals(new Tick(0, 1.0852, 750_000, 400), c.ticks.getLast());
    }

    @Test
    void writerRejectsUndefinedSymbols(@TempDir Path dir) throws Exception {
        try (TickFileWriter w = new TickFileWriter(dir.resolve("x.qflt"))) {
            assertThrows(IllegalStateException.class, () -> w.write(5, 1.0, 1, 1));
        }
    }

    @Test
    void readerRejectsCorruptFiles(@TempDir Path dir) throws Exception {
        Path junk = dir.resolve("junk.qflt");
        Files.write(junk, new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        assertThrows(IOException.class, () -> TickFileReader.replay(junk, (id, p, s, t) -> { }));
    }

    @Test
    void capturesLiveBusTrafficForExactReplay(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("capture.qflt");
        try (HftMarketDataBus bus = new HftMarketDataBus(1 << 12, 8, false)) {
            int eur = bus.registerSymbol("EURUSD");
            int gbp = bus.registerSymbol("GBPUSD");
            try (TickCapture capture = TickCapture.attach(bus, file)) {
                bus.start();
                for (int i = 0; i < 1_000; i++) {
                    bus.publish(i % 2 == 0 ? eur : gbp, 1.08 + i * 1e-5, 100 + i, i);
                }
                bus.stop();   // drains the ring before returning
                assertEquals(1_000, capture.ticksWritten());
            }
        }

        Collector c = new Collector();
        assertEquals(1_000, TickFileReader.replay(file, c));
        assertEquals("EURUSD", c.symbols.get(0));
        assertEquals("GBPUSD", c.symbols.get(1));
        // Exact sequence preserved.
        assertEquals(new Tick(0, 1.08, 100, 0), c.ticks.getFirst());
        assertEquals(new Tick(1, 1.08 + 999 * 1e-5, 1_099, 999), c.ticks.getLast());
    }

    @Test
    void pacedReplayDeliversEverythingScaled(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("paced.qflt");
        try (TickFileWriter w = new TickFileWriter(file)) {
            w.defineSymbol(0, "X");
            for (int i = 0; i < 5; i++) {
                w.write(0, 100 + i, 1, i * 10_000_000L);   // 10ms recorded gaps
            }
        }
        long t0 = System.nanoTime();
        long ticks = TickFileReader.replayPaced(file, (id, p, s, t) -> { }, 100);   // 100x speed
        long elapsed = System.nanoTime() - t0;
        assertEquals(5, ticks);
        assertTrue(elapsed < 500_000_000L, "paced replay too slow: " + elapsed + "ns");
        assertThrows(IllegalArgumentException.class,
                () -> TickFileReader.replayPaced(file, (id, p, s, t) -> { }, 0));
    }
}
