package com.quantfinlib.sbe;

import com.quantfinlib.marketdata.HftMarketDataBus;
import com.quantfinlib.marketdata.StridedSequences;
import com.quantfinlib.util.IdleStrategy;
import com.quantfinlib.util.ThreadPlacement;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ReadableByteChannel;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Binary market-data adapter: decodes {@link TradeFlyweight} frames from a
 * channel into the {@link HftMarketDataBus}. One reused direct buffer and
 * one flyweight, no allocation per message; partial frames are kept by
 * compacting, so TCP segmentation never splits a decode.
 *
 * <p>Framing follows {@link MessageHeader}: frames of other templates are
 * skipped by their block length and counted, a foreign schema id or an
 * impossible block length stops the reader with {@link #failureReason()},
 * and schema-version-1 trade frames (no header) are still decoded.</p>
 *
 * <p>When the bus ring is full, publishing is retried for a bounded budget
 * (default 1 ms); after that the tick is dropped, counted in
 * {@link #droppedTicks()} and the gap flag is raised. On an order-level
 * feed one lost message corrupts the book, so consumers poll
 * {@link #consumeGap()} and resynchronise.</p>
 *
 * <p>Symbol ids are part of the wire contract: both ends register the same
 * symbols in the same order. Single reader thread (the bus's
 * single-producer contract).</p>
 */
public final class BinaryMarketDataClient implements AutoCloseable {

    private final ReadableByteChannel channel;
    private final HftMarketDataBus bus;
    private final ByteBuffer buffer;
    private final TradeFlyweight trade = new TradeFlyweight();
    private final int[] kind = new int[1];
    private Thread readerThread;
    private ThreadFactory threadFactory = ThreadPlacement.daemon("binary-md-client");
    // Back-pressure wait while the bus ring is full; reader thread only.
    private final IdleStrategy retryIdle = IdleStrategy.backoff(100, 0, 10_000, 10_000);
    private final AtomicBoolean gap = new AtomicBoolean();
    private volatile boolean running = true;
    // Reader-thread counters: single writer, opaque increments on padded slots.
    private static final int DECODED = 0;
    private static final int DROPPED = 1;
    private static final int SKIPPED = 2;
    private final StridedSequences counters = new StridedSequences(3);
    private volatile String failureReason;
    private volatile long publishRetryNanos = TimeUnit.MILLISECONDS.toNanos(1);

    public BinaryMarketDataClient(ReadableByteChannel channel, HftMarketDataBus bus) {
        this(channel, bus, 1 << 16);
    }

    public BinaryMarketDataClient(ReadableByteChannel channel, HftMarketDataBus bus,
                                  int bufferBytes) {
        this.channel = channel;
        this.bus = bus;
        this.buffer = ByteBuffer.allocateDirect(bufferBytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Retry budget for a publish into a full ring (set before {@link #start()}). */
    public BinaryMarketDataClient withPublishRetryNanos(long nanos) {
        this.publishRetryNanos = Math.max(0, nanos);
        return this;
    }

    /**
     * Replaces the factory that creates the reader thread (naming, CPU
     * placement; see {@link ThreadPlacement}). Call before {@link #start()}.
     *
     * @param factory thread factory
     * @return this client
     */
    public synchronized BinaryMarketDataClient threadFactory(ThreadFactory factory) {
        if (factory == null) {
            throw new IllegalArgumentException("factory is required");
        }
        this.threadFactory = factory;
        return this;
    }

    public synchronized void start() {
        if (readerThread != null) {
            return;
        }
        readerThread = threadFactory.newThread(this::readLoop);
        readerThread.start();
    }

    public long messagesDecoded() {
        return counters.getOpaque(DECODED);
    }

    /** Ticks dropped because the bus ring stayed full beyond the retry budget. */
    public long droppedTicks() {
        return counters.getOpaque(DROPPED);
    }

    /** Frames of templates this client does not consume, skipped by block length. */
    public long skippedMessages() {
        return counters.getOpaque(SKIPPED);
    }

    /** Returns and clears the gap flag raised by a dropped tick. */
    public boolean consumeGap() {
        return gap.getAndSet(false);
    }

    /** Non-null once the reader stopped on a protocol or I/O error. */
    public String failureReason() {
        return failureReason;
    }

    @Override
    public void close() throws IOException {
        running = false;
        channel.close();
    }

    private void readLoop() {
        try {
            while (running) {
                int read = channel.read(buffer);
                if (read < 0) {
                    return;   // peer closed cleanly
                }
                buffer.flip();
                decodeFrames();
                buffer.compact();   // keep any partial trailing frame
            }
        } catch (IOException e) {
            if (running) {
                failureReason = "read failed: " + e.getMessage();
            }
        } catch (RuntimeException e) {
            failureReason = e.getMessage();
        }
    }

    void decodeFrames() {
        while (true) {
            int position = buffer.position();
            int length = SbeFrames.frameLength(buffer, position, kind);
            if (length == 0) {
                return;   // partial frame: wait for more bytes
            }
            int k = kind[0];
            if (k == TradeFlyweight.TEMPLATE_ID) {
                trade.wrap(buffer, position);
                publish(trade.symbolId(), trade.price(), trade.size(), trade.timestampNanos());
            } else if (k == -1) {
                publish(buffer.getInt(position + SbeFrames.V1_TRADE_SYMBOL),
                        buffer.getDouble(position + SbeFrames.V1_TRADE_PRICE),
                        buffer.getDouble(position + SbeFrames.V1_TRADE_SIZE),
                        buffer.getLong(position + SbeFrames.V1_TRADE_TS));
            } else {
                counters.incrementOpaque(SKIPPED);
            }
            buffer.position(position + length);
        }
    }

    private void publish(int symbolId, double price, double size, long ts) {
        if (!bus.publish(symbolId, price, size, ts) && !retry(symbolId, price, size, ts)) {
            counters.incrementOpaque(DROPPED);
            gap.set(true);
            return;
        }
        counters.incrementOpaque(DECODED);
    }

    private boolean retry(int symbolId, double price, double size, long ts) {
        long deadline = System.nanoTime() + publishRetryNanos;
        retryIdle.reset();
        while (System.nanoTime() - deadline < 0) {
            retryIdle.idle();
            if (bus.publish(symbolId, price, size, ts)) {
                return true;
            }
        }
        return false;
    }
}
