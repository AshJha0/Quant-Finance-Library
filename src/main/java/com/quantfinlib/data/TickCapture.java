package com.quantfinlib.data;

import com.quantfinlib.marketdata.HftMarketDataBus;
import com.quantfinlib.marketdata.TickListener;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Records every tick flowing through an {@link HftMarketDataBus} into a QFLT
 * tick file: attach once, trade or observe as usual, close to flush, then
 * replay the session with {@link TickFileReader}.
 *
 * <p>Writing happens on the bus consumer thread through a 64 KiB block
 * buffer, so a page-cache writeback or disk hiccup stalls that thread. Fine
 * for capture-only or research sessions; when the same bus is trading, use
 * {@link AsyncTickCapture}, which moves the file I/O to its own thread.</p>
 *
 * <p>A disk error never propagates into the bus consumer (where it would
 * stop every other listener): the first failure is kept, {@link #isHealthy()}
 * turns false, later ticks are counted in {@link #droppedTicks()}, and
 * {@link #close()} rethrows it.</p>
 */
public final class TickCapture implements TickListener, AutoCloseable {

    private final TickFileWriter writer;
    private final HftMarketDataBus bus;
    private volatile IOException failure;
    private final AtomicLong droppedTicks = new AtomicLong();

    private TickCapture(HftMarketDataBus bus, Path file, TickFileWriter.ClockDomain domain)
            throws IOException {
        this.bus = bus;
        this.writer = new TickFileWriter(file, domain);
    }

    /**
     * Creates a capture and subscribes it to every symbol on the bus; the
     * file records the clock domain as unknown.
     */
    public static TickCapture attach(HftMarketDataBus bus, Path file) throws IOException {
        return attach(bus, file, TickFileWriter.ClockDomain.UNKNOWN);
    }

    /**
     * As {@link #attach(HftMarketDataBus, Path)}, recording what the bus
     * timestamps measure (epoch nanos for exchange feeds such as
     * {@code feed.WebSocketFeed}, monotonic for {@code System.nanoTime()}).
     */
    public static TickCapture attach(HftMarketDataBus bus, Path file,
                                     TickFileWriter.ClockDomain domain) throws IOException {
        TickCapture capture = new TickCapture(bus, file, domain);
        bus.subscribeAll(capture);
        return capture;
    }

    @Override
    public void onTick(int symbolId, double price, double size, long timestampNanos) {
        if (failure != null) {
            droppedTicks.incrementAndGet();
            return;
        }
        try {
            writer.defineSymbol(symbolId, bus.symbol(symbolId));
            writer.write(symbolId, price, size, timestampNanos);
        } catch (IOException e) {
            failure = e;
            droppedTicks.incrementAndGet();
        }
    }

    public long ticksWritten() {
        return writer.tickCount();
    }

    /** Ticks not recorded because the writer had failed. */
    public long droppedTicks() {
        return droppedTicks.get();
    }

    /** False once a write has failed. */
    public boolean isHealthy() {
        return failure == null;
    }

    /** The first write failure, or null. */
    public IOException failure() {
        return failure;
    }

    /** Writes the last block, forces the file to the device, and rethrows any earlier failure. */
    @Override
    public void close() throws IOException {
        try {
            writer.close();
        } catch (IOException e) {
            if (failure == null) {
                throw e;
            }
        }
        if (failure != null) {
            throw new IOException("tick capture failed", failure);
        }
    }
}
