package com.quantfinlib.data;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.locks.LockSupport;
import java.util.zip.CRC32C;

/**
 * Reader/replayer for QFLT tick files (see {@link TickFileWriter}), versions
 * 1 and 2. Replay is deterministic and allocation-free per tick in version 1;
 * version 2 reads block by block, verifying each block's CRC-32C before
 * delivering any of its ticks.
 *
 * <p>A file that ends early or fails a checksum throws
 * {@link TruncatedTickFileException} after delivering every tick before the
 * damage; {@link TruncatedTickFileException#ticksDelivered()} says how many.</p>
 */
public final class TickFileReader {

    /** Replay callbacks: primitive tick data plus symbol definitions as encountered. */
    public interface ReplayHandler {

        default void onSymbol(int symbolId, String symbol) {
        }

        void onTick(int symbolId, double price, double size, long timestampNanos);
    }

    /**
     * File header facts.
     *
     * @param anchorEpochNanos one instant on the epoch clock (0 in version 1)
     * @param anchorClockNanos the same instant on {@code System.nanoTime()} (0 in version 1)
     */
    public record FileInfo(int version, TickFileWriter.ClockDomain clockDomain,
                           long anchorEpochNanos, long anchorClockNanos) {

        /** Epoch nanoseconds of a timestamp in this file's clock domain. */
        public long toEpochNanos(long timestampNanos) {
            return switch (clockDomain) {
                case EPOCH -> timestampNanos;
                case MONOTONIC -> anchorEpochNanos + (timestampNanos - anchorClockNanos);
                case UNKNOWN -> throw new IllegalStateException("clock domain not recorded");
            };
        }
    }

    /** Thrown when a file is cut short or corrupt; ticks before the damage were delivered. */
    public static final class TruncatedTickFileException extends IOException
            implements com.quantfinlib.core.QuantFinException {

        private static final long serialVersionUID = 1L;

        private final long ticksDelivered;

        TruncatedTickFileException(String message, long ticksDelivered, Throwable cause) {
            super(message + " after " + ticksDelivered + " ticks", cause);
            this.ticksDelivered = ticksDelivered;
        }

        public long ticksDelivered() {
            return ticksDelivered;
        }
    }

    private TickFileReader() {
    }

    /** Reads the header of a QFLT file. */
    public static FileInfo info(Path path) throws IOException {
        try (DataInputStream in = new DataInputStream(Files.newInputStream(path))) {
            return readHeader(in, path);
        } catch (EOFException e) {
            throw new IOException("not a QFLT tick file (too short): " + path, e);
        }
    }

    private static FileInfo readHeader(DataInputStream in, Path path) throws IOException {
        if (in.readInt() != TickFileWriter.MAGIC) {
            throw new IOException("not a QFLT tick file: " + path);
        }
        byte version = in.readByte();
        if (version == TickFileWriter.VERSION_1) {
            return new FileInfo(1, TickFileWriter.ClockDomain.UNKNOWN, 0, 0);
        }
        if (version != TickFileWriter.VERSION) {
            throw new IOException("unsupported QFLT version " + version);
        }
        int domain = in.readByte();
        TickFileWriter.ClockDomain[] domains = TickFileWriter.ClockDomain.values();
        if (domain < 0 || domain >= domains.length) {
            throw new IOException("unknown QFLT clock domain " + domain);
        }
        return new FileInfo(2, domains[domain], in.readLong(), in.readLong());
    }

    /** Replays the file as fast as possible. Returns the number of ticks delivered. */
    public static long replay(Path path, ReplayHandler handler) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                Files.newInputStream(path), 1 << 20))) {
            FileInfo info;
            try {
                info = readHeader(in, path);
            } catch (EOFException e) {
                throw new TruncatedTickFileException("truncated QFLT header: " + path, 0, e);
            }
            return info.version() == 1 ? replayV1(in, handler, path) : replayV2(in, handler, path);
        }
    }

    private static long replayV1(DataInputStream in, ReplayHandler handler, Path path)
            throws IOException {
        long ticks = 0;
        try {
            while (true) {
                int type = in.read();
                if (type < 0) {
                    return ticks;   // clean EOF
                }
                if (type == TickFileWriter.TYPE_SYMBOL) {
                    int id = in.readInt();
                    byte[] name = new byte[in.readUnsignedShort()];
                    in.readFully(name);
                    handler.onSymbol(id, new String(name, StandardCharsets.UTF_8));
                } else if (type == TickFileWriter.TYPE_TICK) {
                    int id = in.readInt();
                    double price = in.readDouble();
                    double size = in.readDouble();
                    long ts = in.readLong();
                    handler.onTick(id, price, size, ts);
                    ticks++;
                } else {
                    throw new TruncatedTickFileException("corrupt record type " + type, ticks, null);
                }
            }
        } catch (EOFException e) {
            throw new TruncatedTickFileException("truncated QFLT file " + path, ticks, e);
        }
    }

    private static long replayV2(InputStream in, ReplayHandler handler, Path path)
            throws IOException {
        long ticks = 0;
        byte[] head = new byte[8];
        byte[] payload = new byte[TickFileWriter.BLOCK_BYTES];
        byte[] crcBytes = new byte[4];
        CRC32C crc = new CRC32C();
        while (true) {
            int got = in.readNBytes(head, 0, 8);
            if (got == 0) {
                return ticks;   // clean end on a block boundary
            }
            if (got < 8) {
                throw new TruncatedTickFileException("truncated block header in " + path, ticks, null);
            }
            ByteBuffer h = ByteBuffer.wrap(head);
            int length = h.getInt();
            int records = h.getInt();
            if (length < 0 || length > 64 << 20 || records < 0) {
                throw new TruncatedTickFileException("corrupt block header in " + path, ticks, null);
            }
            if (payload.length < length) {
                payload = new byte[length];
            }
            if (in.readNBytes(payload, 0, length) < length || in.readNBytes(crcBytes, 0, 4) < 4) {
                throw new TruncatedTickFileException("truncated block in " + path, ticks, null);
            }
            crc.reset();
            crc.update(payload, 0, length);
            if ((int) crc.getValue() != ByteBuffer.wrap(crcBytes).getInt()) {
                throw new TruncatedTickFileException("block checksum mismatch in " + path, ticks, null);
            }
            ByteBuffer b = ByteBuffer.wrap(payload, 0, length);
            for (int r = 0; r < records; r++) {
                byte type = b.get();
                if (type == TickFileWriter.TYPE_SYMBOL) {
                    int id = b.getInt();
                    byte[] name = new byte[b.getShort() & 0xFFFF];
                    b.get(name);
                    handler.onSymbol(id, new String(name, StandardCharsets.UTF_8));
                } else if (type == TickFileWriter.TYPE_TICK) {
                    handler.onTick(b.getInt(), b.getDouble(), b.getDouble(), b.getLong());
                    ticks++;
                } else {
                    throw new TruncatedTickFileException("corrupt record type " + type, ticks, null);
                }
            }
        }
    }

    /**
     * Replays reproducing the recorded inter-tick gaps scaled by
     * {@code speedMultiplier} (2.0 = twice real time; individual gaps are
     * capped at 10 s). Gaps are only meaningful within one clock domain; a
     * file's domain is in {@link #info}.
     */
    public static long replayPaced(Path path, ReplayHandler handler, double speedMultiplier)
            throws IOException {
        if (speedMultiplier <= 0) {
            throw new IllegalArgumentException("speedMultiplier must be positive");
        }
        long[] prevTs = {Long.MIN_VALUE};
        return replay(path, new ReplayHandler() {
            @Override
            public void onSymbol(int symbolId, String symbol) {
                handler.onSymbol(symbolId, symbol);
            }

            @Override
            public void onTick(int symbolId, double price, double size, long timestampNanos) {
                if (prevTs[0] != Long.MIN_VALUE) {
                    long gap = (long) ((timestampNanos - prevTs[0]) / speedMultiplier);
                    if (gap > 0) {
                        LockSupport.parkNanos(Math.min(gap, 10_000_000_000L));
                    }
                }
                prevTs[0] = timestampNanos;
                handler.onTick(symbolId, price, size, timestampNanos);
            }
        });
    }
}
