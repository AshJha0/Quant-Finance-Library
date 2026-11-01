package com.quantfinlib.data;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.zip.CRC32C;

/**
 * Writer for the QFLT binary tick format, version 2: compact capture of
 * tick streams for deterministic replay.
 *
 * <h2>Format (big-endian)</h2>
 * <pre>
 * header  int magic "QFLT" | byte version = 2 | byte clockDomain
 *         | long anchorEpochNanos | long anchorClockNanos
 * block   int payloadLength | int recordCount | payload | int crc32c(payload)
 * record  byte 1 = symbol: int id | short nameLength | UTF-8 name
 *         byte 0 = tick:   int symbolId | double price | double size | long timestampNanos
 * </pre>
 * {@code clockDomain} says what the tick timestamps are ({@link ClockDomain});
 * the anchor pair records one instant on both clocks, so monotonic
 * timestamps can be placed on the calendar. Records are grouped in blocks
 * of about 64 KiB, each with a CRC-32C, so a reader detects corruption and
 * a torn tail to the block.
 *
 * <p>Symbol definitions may appear anywhere before the first tick that
 * references them. {@link #close()} writes the last block and forces the
 * file to the device. Version-1 files (no header fields, no blocks) are
 * still read by {@link TickFileReader}.</p>
 */
public final class TickFileWriter implements AutoCloseable {

    static final int MAGIC = 0x51464C54;   // "QFLT"
    static final byte VERSION_1 = 1;
    static final byte VERSION = 2;
    static final byte TYPE_TICK = 0;
    static final byte TYPE_SYMBOL = 1;
    static final int HEADER_BYTES = 4 + 1 + 1 + 8 + 8;
    static final int BLOCK_BYTES = 64 * 1024;

    /** What the tick timestamps measure. */
    public enum ClockDomain {
        /** Not recorded (version-1 files, or the writer was not told). */
        UNKNOWN,
        /** {@code System.nanoTime()}: monotonic, arbitrary origin, one process only. */
        MONOTONIC,
        /** Nanoseconds since the Unix epoch, UTC (exchange event time). */
        EPOCH
    }

    private final FileChannel channel;
    private final ClockDomain clockDomain;
    private ByteBuffer block = ByteBuffer.allocate(BLOCK_BYTES);
    private int blockRecords;
    private boolean[] defined = new boolean[64];
    private long tickCount;
    private boolean closed;

    /** Writes a file whose clock domain is {@link ClockDomain#UNKNOWN}. */
    public TickFileWriter(Path path) throws IOException {
        this(path, ClockDomain.UNKNOWN);
    }

    /**
     * @param clockDomain what the timestamps passed to {@link #write} measure;
     *                    the anchor is taken now from both system clocks
     */
    public TickFileWriter(Path path, ClockDomain clockDomain) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        this.clockDomain = clockDomain;
        this.channel = FileChannel.open(path, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        java.time.Instant now = java.time.Instant.now();
        long anchorClock = System.nanoTime();
        long anchorEpoch = now.getEpochSecond() * 1_000_000_000L + now.getNano();
        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES)
                .putInt(MAGIC).put(VERSION).put((byte) clockDomain.ordinal())
                .putLong(anchorEpoch).putLong(anchorClock).flip();
        writeFully(header);
    }

    public ClockDomain clockDomain() {
        return clockDomain;
    }

    /** Registers a symbol id (idempotent; must precede its first tick). */
    public void defineSymbol(int symbolId, String symbol) throws IOException {
        if (symbolId >= defined.length) {
            defined = Arrays.copyOf(defined, Integer.highestOneBit(symbolId) * 2);
        }
        if (defined[symbolId]) {
            return;
        }
        byte[] name = symbol.getBytes(StandardCharsets.UTF_8);
        if (name.length > 0xFFFF) {
            throw new IllegalArgumentException("symbol name longer than 65535 bytes");
        }
        ensureRoom(1 + 4 + 2 + name.length);
        block.put(TYPE_SYMBOL).putInt(symbolId).putShort((short) name.length).put(name);
        blockRecords++;
        defined[symbolId] = true;
    }

    public void write(int symbolId, double price, double size, long timestampNanos) throws IOException {
        if (symbolId < 0 || symbolId >= defined.length || !defined[symbolId]) {
            throw new IllegalStateException("symbol id " + symbolId + " not defined before first tick");
        }
        ensureRoom(29);
        block.put(TYPE_TICK).putInt(symbolId).putDouble(price).putDouble(size)
                .putLong(timestampNanos);
        blockRecords++;
        tickCount++;
    }

    public long tickCount() {
        return tickCount;
    }

    /** Writes the current block (if any) without forcing it to the device. */
    public void flush() throws IOException {
        if (blockRecords == 0) {
            return;
        }
        block.flip();
        int length = block.remaining();
        CRC32C crc = new CRC32C();
        crc.update(block.array(), 0, length);
        ByteBuffer head = ByteBuffer.allocate(8).putInt(length).putInt(blockRecords).flip();
        ByteBuffer tail = ByteBuffer.allocate(4).putInt((int) crc.getValue()).flip();
        writeFully(head);
        writeFully(block);
        writeFully(tail);
        block.clear();
        blockRecords = 0;
    }

    /** Writes the last block and forces the file to the device. */
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            flush();
            channel.force(true);
        } finally {
            channel.close();
        }
    }

    private void ensureRoom(int bytes) throws IOException {
        if (block.remaining() < bytes) {
            flush();
            if (block.capacity() < bytes) {
                block = ByteBuffer.allocate(bytes);
            }
        }
    }

    private void writeFully(ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) {
            channel.write(buf);
        }
    }
}
