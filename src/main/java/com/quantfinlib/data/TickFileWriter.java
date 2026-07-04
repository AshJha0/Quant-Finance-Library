package com.quantfinlib.data;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Writer for the QFLT binary tick format — compact capture of live tick
 * streams for deterministic replay (28 bytes per tick, buffered sequential
 * writes).
 *
 * <p>Format: magic {@code "QFLT"} + version byte, then framed records:
 * type 1 = symbol definition (int id, UTF-8 name), type 0 = tick
 * (int symbolId, double price, double size, long timestampNanos). Symbol
 * definitions may appear anywhere before the first tick that references
 * them, so symbols can be added mid-capture.</p>
 */
public final class TickFileWriter implements AutoCloseable {

    static final int MAGIC = 0x51464C54;   // "QFLT"
    static final byte VERSION = 1;
    static final byte TYPE_TICK = 0;
    static final byte TYPE_SYMBOL = 1;

    private final DataOutputStream out;
    private boolean[] defined = new boolean[64];
    private long tickCount;

    public TickFileWriter(Path path) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        this.out = new DataOutputStream(new BufferedOutputStream(
                Files.newOutputStream(path), 1 << 20));
        out.writeInt(MAGIC);
        out.writeByte(VERSION);
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
        out.writeByte(TYPE_SYMBOL);
        out.writeInt(symbolId);
        out.writeShort(name.length);
        out.write(name);
        defined[symbolId] = true;
    }

    public void write(int symbolId, double price, double size, long timestampNanos) throws IOException {
        if (symbolId >= defined.length || !defined[symbolId]) {
            throw new IllegalStateException("symbol id " + symbolId + " not defined before first tick");
        }
        out.writeByte(TYPE_TICK);
        out.writeInt(symbolId);
        out.writeDouble(price);
        out.writeDouble(size);
        out.writeLong(timestampNanos);
        tickCount++;
    }

    public long tickCount() {
        return tickCount;
    }

    @Override
    public void close() throws IOException {
        out.close();
    }
}
