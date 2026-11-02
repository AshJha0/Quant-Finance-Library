package com.quantfinlib.marketdata;

import com.quantfinlib.marketdata.HftMarketDataBus;
import com.quantfinlib.marketdata.tick.TickFileReader;
import com.quantfinlib.marketdata.tick.TickFileWriter;
import java.io.DataOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The asynchronous recorder surfaces a writer failure; split out of the main
 * module's TickFileV2Test in 3.0 (the recorders moved to the low-latency module).
 */
class AsyncTickCaptureFailureTest {

    @Test
    void asyncCaptureSurfacesAWriterFailure(@TempDir Path dir) throws Exception {
        try (HftMarketDataBus bus = new HftMarketDataBus(1 << 10, 4, false)) {
            bus.registerSymbol("EURUSD");
            AsyncTickCapture capture = AsyncTickCapture.attach(bus, dir.resolve("x.qflt"), 1 << 10);
            // A tick for a symbol id the bus never registered: the writer
            // thread fails resolving its name.
            capture.onTick(3, 1.0, 1.0, 1L);
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (capture.isHealthy() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertFalse(capture.isHealthy(), "the failure must be visible");
            assertNotNull(capture.failure());
            capture.onTick(0, 1.0, 1.0, 2L);
            assertEquals(1, capture.droppedTicks(), "ticks after the failure count as dropped");
            assertThrows(java.io.IOException.class, capture::close);
        }
    }
}
