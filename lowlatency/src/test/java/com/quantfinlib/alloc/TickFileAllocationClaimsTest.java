package com.quantfinlib.alloc;

import com.quantfinlib.marketdata.tick.TickFileReader;
import com.quantfinlib.marketdata.tick.TickFileWriter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.quantfinlib.alloc.AllocationProbe.assertAllocationFree;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The allocation claims of {@code marketdata.tick}, which moved from the
 * stable module to this one in 4.0 (the tests moved from
 * {@code AllocationClaimsTest} in core unchanged).
 */
@Tag("allocation")
class TickFileAllocationClaimsTest {

    // ------------------------------------------------------------------
    // marketdata.tick
    // ------------------------------------------------------------------

    private static long threadBytes() {
        return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean())
                .getCurrentThreadAllocatedBytes();
    }

    private static void writeV1(Path f, int ticks) throws Exception {
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(f)))) {
            out.writeInt(0x51464C54 /* "QFLT", TickFileWriter.MAGIC */);
            out.writeByte(1);
            out.writeByte(1 /* TYPE_SYMBOL */);
            out.writeInt(0);
            byte[] name = "BTC".getBytes(StandardCharsets.UTF_8);
            out.writeShort(name.length);
            out.write(name);
            for (int i = 0; i < ticks; i++) {
                out.writeByte(0 /* TYPE_TICK */);
                out.writeInt(0);
                out.writeDouble(100 + (i & 63));
                out.writeDouble(1);
                out.writeLong(i);
            }
        }
    }

    /**
     * "Allocation-free per tick" is a per-tick claim on a call that opens a
     * stream (a fixed cost), so the test replays a small and a large v1 file
     * and requires the extra ticks to add essentially nothing.
     */
    @Test
    void tickFileV1ReplayIsAllocationFreePerTick(@TempDir Path dir) throws Exception {
        AllocationProbe.bestBatchBytes(i -> 0);   // enables thread allocation counting (or skips)
        Path small = dir.resolve("small.qflt");
        Path large = dir.resolve("large.qflt");
        int extra = 200_000;
        writeV1(small, 1_000);
        writeV1(large, 1_000 + extra);
        double[] acc = new double[1];
        TickFileReader.ReplayHandler h = (id, price, size, ts) -> acc[0] += price;
        for (int w = 0; w < 20; w++) {
            TickFileReader.replay(small, h);
            TickFileReader.replay(large, h);
        }
        long best = Long.MAX_VALUE;
        for (int a = 0; a < 5; a++) {
            long b0 = threadBytes();
            TickFileReader.replay(small, h);
            long b1 = threadBytes();
            long ticks = TickFileReader.replay(large, h);
            long b2 = threadBytes();
            assertEquals(1_000 + extra, ticks);
            best = Math.min(best, (b2 - b1) - (b1 - b0));
        }
        assertTrue(best <= AllocationProbe.TOLERANCE_BYTES,
                "TickFileReader v1 replay per tick: " + extra + " extra ticks allocated " + best + " bytes");
    }

    @Test
    void tickFileWriterSteadyStreamIsAllocationFree(@TempDir Path dir) throws Exception {
        try (TickFileWriter w = new TickFileWriter(dir.resolve("w.qflt"))) {
            w.defineSymbol(0, "BTC");
            // 29 bytes per tick: tens of thousands of calls cross many block flushes
            assertAllocationFree("TickFileWriter steady stream (per block)", i -> {
                w.write(0, 100 + (i & 63), 1, i);
                return w.tickCount();
            });
        }
    }

}
