package com.quantfinlib.data;

import com.quantfinlib.marketdata.HftMarketDataBus;
import com.quantfinlib.marketdata.TickRingBuffer;
import com.quantfinlib.marketdata.TickListener;
import com.quantfinlib.util.IdleStrategy;
import com.quantfinlib.util.ThreadPlacement;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tick capture with the file I/O taken off the bus consumer thread — the
 * hot-lane variant of {@link TickCapture}. The consumer thread only
 * publishes into a private {@link TickRingBuffer}; a dedicated writer
 * thread drains it into the QFLT file. A page-cache writeback, AV scan or
 * disk hiccup then stalls the <em>writer</em>, never the trading loop.
 *
 * <p>The backpressure policy suits a recorder on a trading
 * path: when the ring is full (writer stalled longer than the buffer
 * absorbs), ticks are <b>dropped from the capture</b> — counted in
 * {@link #droppedTicks}, never blocking the consumer. A recording gap you
 * can see beats latency you can't explain. Size the ring for the worst
 * stall you tolerate: capacity 1M ≈ 28 MB ≈ several seconds of a busy feed.</p>
 *
 * <p>A writer failure (disk full, I/O error) stops the writer thread and is
 * kept: {@link #failure()} returns it, {@link #isHealthy()} turns false,
 * ticks from then on count as dropped, and {@link #close()} rethrows it.</p>
 *
 * <p>Use {@link TickCapture} when simplicity wins (research replays,
 * capture-only sessions); use this when the same bus is trading.</p>
 */
public final class AsyncTickCapture implements TickListener, AutoCloseable {

    private final TickRingBuffer ring;
    private final TickFileWriter writer;
    private final HftMarketDataBus bus;
    private final Thread drainThread;
    private final IdleStrategy idle;
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean running = true;
    private volatile Throwable failure;

    private AsyncTickCapture(HftMarketDataBus bus, Path file, int ringCapacity,
                             TickFileWriter.ClockDomain domain, IdleStrategy idle,
                             ThreadFactory threadFactory) throws IOException {
        if (idle == null || threadFactory == null) {
            throw new IllegalArgumentException("idle strategy and thread factory are required");
        }
        this.bus = bus;
        this.idle = idle;
        this.writer = new TickFileWriter(file, domain);
        this.ring = new TickRingBuffer(ringCapacity);
        this.drainThread = threadFactory.newThread(this::drainLoop);
    }

    /** Creates the capture, subscribes it to every symbol, starts the writer. */
    public static AsyncTickCapture attach(HftMarketDataBus bus, Path file, int ringCapacity)
            throws IOException {
        return attach(bus, file, ringCapacity, TickFileWriter.ClockDomain.UNKNOWN);
    }

    /** As {@link #attach(HftMarketDataBus, Path, int)}, recording the timestamps' clock domain. */
    public static AsyncTickCapture attach(HftMarketDataBus bus, Path file, int ringCapacity,
                                          TickFileWriter.ClockDomain domain) throws IOException {
        return attach(bus, file, ringCapacity, domain,
                IdleStrategy.backoff(0, 0, 10_000, 1_000_000), ThreadPlacement.daemon("tick-capture-writer"));
    }

    /**
     * As {@link #attach(HftMarketDataBus, Path, int, TickFileWriter.ClockDomain)},
     * with the writer thread's idle strategy and thread factory chosen by the caller.
     *
     * @param bus           bus to capture
     * @param file          output file
     * @param ringCapacity  hand-off ring size (power of two)
     * @param domain        clock domain of the tick timestamps
     * @param idle          what the writer thread does when the ring is empty (owned by it)
     * @param threadFactory creates the writer thread (naming, CPU placement)
     * @return the running capture
     * @throws IOException if the file cannot be created
     */
    public static AsyncTickCapture attach(HftMarketDataBus bus, Path file, int ringCapacity,
                                          TickFileWriter.ClockDomain domain, IdleStrategy idle,
                                          ThreadFactory threadFactory) throws IOException {
        AsyncTickCapture capture = new AsyncTickCapture(bus, file, ringCapacity, domain, idle,
                threadFactory);
        bus.subscribeAll(capture);
        capture.drainThread.start();
        return capture;
    }

    /** The hot path: one ring publish; a full ring drops (counted), never blocks. */
    @Override
    public void onTick(int symbolId, double price, double size, long timestampNanos) {
        if (failure != null || !ring.publish(symbolId, price, size, timestampNanos)) {
            dropped.incrementAndGet();
        }
    }

    /** Writer thread: batch-drains the ring into the QFLT file. */
    private void drainLoop() {
        TickListener sink = (symbolId, price, size, timestampNanos) -> {
            try {
                writer.defineSymbol(symbolId, bus.symbol(symbolId));
                writer.write(symbolId, price, size, timestampNanos);
            } catch (IOException e) {
                throw new UncheckedIOException("tick capture failed", e);
            }
        };
        try {
            while (running) {
                // The writer thread has no latency budget, only the bus
                // consumer does; the default strategy parks with back-off.
                idle.idle(ring.drainTo(sink, 4_096));
            }
            // Final drain so close() loses nothing that made it into the ring.
            while (ring.drainTo(sink, 4_096) > 0) {
                // keep draining
            }
        } catch (RuntimeException | Error e) {
            failure = e instanceof UncheckedIOException u ? u.getCause() : e;
            running = false;
        }
    }

    /** False once the writer thread has failed. */
    public boolean isHealthy() {
        return failure == null;
    }

    /** The writer thread's failure, or null. */
    public Throwable failure() {
        return failure;
    }

    /** Ticks dropped because the ring was full (writer stalled) — monitor this. */
    public long droppedTicks() {
        return dropped.get();
    }

    public long ticksWritten() {
        return writer.tickCount();
    }

    /** Stops the writer (draining what remains) and closes the file. */
    @Override
    public void close() throws IOException {
        running = false;
        try {
            drainThread.join(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            writer.close();
        } catch (IOException e) {
            if (failure == null) {
                throw e;
            }
        }
        Throwable f = failure;
        if (f != null) {
            throw new IOException("tick capture writer failed", f);
        }
    }
}
