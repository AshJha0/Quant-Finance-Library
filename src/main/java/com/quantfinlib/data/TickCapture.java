package com.quantfinlib.data;

import com.quantfinlib.marketdata.HftMarketDataBus;
import com.quantfinlib.marketdata.TickListener;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/**
 * Records every tick flowing through an {@link HftMarketDataBus} into a QFLT
 * tick file: attach once, trade/observe as usual, close to flush — then
 * replay the session deterministically with {@link TickFileReader}.
 *
 * <p>Writing happens on the bus consumer thread through a 1 MB buffer
 * (~28 bytes/tick), which is comfortably below the bus's throughput; a disk
 * error surfaces as {@link UncheckedIOException} on that thread.
 * <b>Latency note</b>: a page-cache writeback or disk hiccup therefore
 * stalls the consumer thread. Fine for capture-only or research sessions;
 * when the same bus is TRADING, use {@link AsyncTickCapture}, which moves
 * the file I/O to a dedicated writer thread behind a ring.</p>
 */
public final class TickCapture implements TickListener, AutoCloseable {

    private final TickFileWriter writer;
    private final HftMarketDataBus bus;

    private TickCapture(HftMarketDataBus bus, Path file) throws IOException {
        this.bus = bus;
        this.writer = new TickFileWriter(file);
    }

    /** Creates a capture and subscribes it to every symbol on the bus. */
    public static TickCapture attach(HftMarketDataBus bus, Path file) throws IOException {
        TickCapture capture = new TickCapture(bus, file);
        bus.subscribeAll(capture);
        return capture;
    }

    @Override
    public void onTick(int symbolId, double price, double size, long timestampNanos) {
        try {
            writer.defineSymbol(symbolId, bus.symbol(symbolId));
            writer.write(symbolId, price, size, timestampNanos);
        } catch (IOException e) {
            throw new UncheckedIOException("tick capture failed", e);
        }
    }

    public long ticksWritten() {
        return writer.tickCount();
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }
}
