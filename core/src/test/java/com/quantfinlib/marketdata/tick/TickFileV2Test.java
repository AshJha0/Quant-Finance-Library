package com.quantfinlib.marketdata.tick;

import com.quantfinlib.marketdata.tick.TickFileReader;
import com.quantfinlib.marketdata.tick.TickFileWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** QFLT version 2: clock domain, block CRC, truncation count, v1 compatibility (I2, I3). */
class TickFileV2Test {

    private static Path write(Path file, int ticks, TickFileWriter.ClockDomain domain) throws Exception {
        try (TickFileWriter w = new TickFileWriter(file, domain)) {
            w.defineSymbol(0, "EURUSD");
            for (int i = 0; i < ticks; i++) {
                w.write(0, 1.0 + i * 1e-5, 1_000, 1_000L + i);
            }
        }
        return file;
    }

    @Test
    void headerRecordsTheClockDomainAndAnAnchor(@TempDir Path dir) throws Exception {
        Path f = write(dir.resolve("a.qflt"), 3, TickFileWriter.ClockDomain.EPOCH);
        TickFileReader.FileInfo info = TickFileReader.info(f);
        assertEquals(2, info.version());
        assertEquals(TickFileWriter.ClockDomain.EPOCH, info.clockDomain());
        assertTrue(info.anchorEpochNanos() > 1_700_000_000_000_000_000L, "anchor is now-ish");
        assertEquals(123L, info.toEpochNanos(123L));

        TickFileReader.FileInfo mono = new TickFileReader.FileInfo(2,
                TickFileWriter.ClockDomain.MONOTONIC, 1_000_000L, 500L);
        assertEquals(1_000_100L, mono.toEpochNanos(600L));
    }

    @Test
    void truncationReportsHowManyTicksWereDelivered(@TempDir Path dir) throws Exception {
        // ~2300 ticks per 64 KiB block: 5000 ticks span three blocks.
        Path f = write(dir.resolve("t.qflt"), 5000, TickFileWriter.ClockDomain.MONOTONIC);
        long size = Files.size(f);
        try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "rw")) {
            raf.setLength(size - 10);   // tear the last block
        }
        long[] seen = {0};
        TickFileReader.TruncatedTickFileException e = assertThrows(
                TickFileReader.TruncatedTickFileException.class,
                () -> TickFileReader.replay(f, (id, p, s, t) -> seen[0]++));
        assertEquals(seen[0], e.ticksDelivered());
        // Block 1: a 13-byte symbol record, then floor((65536 - 13) / 29) = 2259 ticks;
        // block 2: floor(65536 / 29) = 2259 ticks; block 3 (torn) is not delivered.
        assertEquals(4518, e.ticksDelivered());
    }

    @Test
    void aFlippedBitFailsTheBlockChecksumBeforeAnyOfItsTicksAreDelivered(@TempDir Path dir)
            throws Exception {
        Path f = write(dir.resolve("c.qflt"), 10, TickFileWriter.ClockDomain.UNKNOWN);
        try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "rw")) {
            long pos = raf.length() - 20;   // inside the last tick's payload
            raf.seek(pos);
            int b = raf.read();
            raf.seek(pos);
            raf.write(b ^ 0x10);
        }
        long[] seen = {0};
        TickFileReader.TruncatedTickFileException e = assertThrows(
                TickFileReader.TruncatedTickFileException.class,
                () -> TickFileReader.replay(f, (id, p, s, t) -> seen[0]++));
        assertEquals(0, seen[0], "a corrupt block delivers nothing");
        assertTrue(e.getMessage().contains("checksum"));
    }

    @Test
    void versionOneFilesStillReplay(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("v1.qflt");
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(f))) {
            out.writeInt(TickFileWriter.MAGIC);
            out.writeByte(1);
            out.writeByte(TickFileWriter.TYPE_SYMBOL);
            out.writeInt(0);
            byte[] name = "BTC".getBytes(StandardCharsets.UTF_8);
            out.writeShort(name.length);
            out.write(name);
            for (int i = 0; i < 3; i++) {
                out.writeByte(TickFileWriter.TYPE_TICK);
                out.writeInt(0);
                out.writeDouble(100 + i);
                out.writeDouble(1);
                out.writeLong(i);
            }
            out.writeByte(TickFileWriter.TYPE_TICK);   // torn fourth tick
            out.writeInt(0);
        }
        assertEquals(1, TickFileReader.info(f).version());
        assertEquals(TickFileWriter.ClockDomain.UNKNOWN, TickFileReader.info(f).clockDomain());
        List<Double> prices = new ArrayList<>();
        TickFileReader.TruncatedTickFileException e = assertThrows(
                TickFileReader.TruncatedTickFileException.class,
                () -> TickFileReader.replay(f, (id, p, s, t) -> prices.add(p)));
        assertEquals(3, e.ticksDelivered());
        assertEquals(List.of(100.0, 101.0, 102.0), prices);
    }

    @Test
    void aFileWithTheWrongMagicIsALibraryFault(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("junk.qflt");
        java.nio.file.Files.write(file, new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        var e = assertThrows(com.quantfinlib.core.MalformedFileException.class,
                () -> TickFileReader.info(file));
        org.junit.jupiter.api.Assertions.assertInstanceOf(
                com.quantfinlib.core.QuantFinException.class, e);
    }
}
