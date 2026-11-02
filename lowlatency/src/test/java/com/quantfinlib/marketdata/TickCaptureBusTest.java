package com.quantfinlib.marketdata;

import com.quantfinlib.marketdata.HftMarketDataBus;
import com.quantfinlib.marketdata.tick.TickFileReader;
import com.quantfinlib.marketdata.tick.TickFileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Recording the live bus into a QFLT file, split out of the main module's
 * TickFileTest in 3.0 (the recorders moved to the low-latency module).
 */
class TickCaptureBusTest {

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
}
