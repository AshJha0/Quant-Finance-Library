package com.quantfinlib.data;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.locks.LockSupport;

/**
 * Reader/replayer for QFLT tick files (see {@link TickFileWriter}). Replay is
 * deterministic and allocation-free per tick after the symbol definitions —
 * record a live session once, then run strategy, latency, and microstructure
 * experiments against identical real tick sequences.
 */
public final class TickFileReader {

    /** Replay callbacks: primitive tick data plus symbol definitions as encountered. */
    public interface ReplayHandler {

        default void onSymbol(int symbolId, String symbol) {
        }

        void onTick(int symbolId, double price, double size, long timestampNanos);
    }

    private TickFileReader() {
    }

    /** Replays the file as fast as possible. Returns the number of ticks delivered. */
    public static long replay(Path path, ReplayHandler handler) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                Files.newInputStream(path), 1 << 20))) {
            if (in.readInt() != TickFileWriter.MAGIC) {
                throw new IOException("not a QFLT tick file: " + path);
            }
            byte version = in.readByte();
            if (version != TickFileWriter.VERSION) {
                throw new IOException("unsupported QFLT version " + version);
            }
            long ticks = 0;
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
                    handler.onTick(in.readInt(), in.readDouble(), in.readDouble(), in.readLong());
                    ticks++;
                } else {
                    throw new IOException("corrupt record type " + type + " after " + ticks + " ticks");
                }
            }
        } catch (EOFException e) {
            throw new IOException("truncated QFLT file: " + path, e);
        }
    }

    /**
     * Replays reproducing the recorded inter-tick gaps scaled by
     * {@code speedMultiplier} (2.0 = twice real time; individual gaps are
     * capped at 10 s). For live-like feeds into the HFT bus.
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
