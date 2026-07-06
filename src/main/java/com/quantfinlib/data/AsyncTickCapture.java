package com.quantfinlib.data;

import com.quantfinlib.marketdata.HftMarketDataBus;
import com.quantfinlib.marketdata.TickRingBuffer;
import com.quantfinlib.marketdata.TickListener;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tick capture with the file I/O taken OFF the bus consumer thread — the
 * hot-lane variant of {@link TickCapture}. The consumer thread only
 * publishes into a private {@link TickRingBuffer}; a dedicated writer
 * thread drains it into the QFLT file. A page-cache writeback, AV scan or
 * disk hiccup then stalls the <em>writer</em>, never the trading loop.
 *
 * <p>The backpressure policy is the honest one for a recorder on a trading
 * path: when the ring is full (writer stalled longer than the buffer
 * absorbs), ticks are <b>dropped from the capture</b> — counted in
 * {@link #droppedTicks}, never blocking the consumer. A recording gap you
 * can see beats latency you can't explain. Size the ring for the worst
 * stall you tolerate: capacity 1M ≈ 28 MB ≈ several seconds of a busy feed.</p>
 *
 * <p>Use {@link TickCapture} when simplicity wins (research replays,
 * capture-only sessions); use this when the same bus is trading.</p>
 */
public final class AsyncTickCapture implements TickListener, AutoCloseable {

    private final TickRingBuffer ring;
    private final TickFileWriter writer;
    private final HftMarketDataBus bus;
    private final Thread drainThread;
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean running = true;

    private AsyncTickCapture(HftMarketDataBus bus, Path file, int ringCapacity)
            throws IOException {
        this.bus = bus;
        this.writer = new TickFileWriter(file);
        this.ring = new TickRingBuffer(ringCapacity);
        this.drainThread = new Thread(this::drainLoop, "tick-capture-writer");
        this.drainThread.setDaemon(true);
    }

    /** Creates the capture, subscribes it to every symbol, starts the writer. */
    public static AsyncTickCapture attach(HftMarketDataBus bus, Path file, int ringCapacity)
            throws IOException {
        AsyncTickCapture capture = new AsyncTickCapture(bus, file, ringCapacity);
        bus.subscribeAll(capture);
        capture.drainThread.start();
        return capture;
    }

    /** The hot path: one ring publish; a full ring drops (counted), never blocks. */
    @Override
    public void onTick(int symbolId, double price, double size, long timestampNanos) {
        if (!ring.publish(symbolId, price, size, timestampNanos)) {
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
        while (running) {
            if (ring.drainTo(sink, 4_096) == 0) {
                // Idle: yield rather than spin — the writer thread has no
                // latency budget, only the consumer thread does.
                java.util.concurrent.locks.LockSupport.parkNanos(100_000);
            }
        }
        // Final drain so close() loses nothing that made it into the ring.
        while (ring.drainTo(sink, 4_096) > 0) {
            // keep draining
        }
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
        writer.close();
    }
}
