package com.quantfinlib.sbe;

import com.quantfinlib.marketdata.HftMarketDataBus;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ReadableByteChannel;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

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
    private final Thread readerThread;
    private final AtomicBoolean gap = new AtomicBoolean();
    private volatile boolean running = true;
    private volatile long messagesDecoded;
    private volatile long droppedTicks;
    private volatile long skippedMessages;
    private volatile String failureReason;
    private long publishRetryNanos = TimeUnit.MILLISECONDS.toNanos(1);

    public BinaryMarketDataClient(ReadableByteChannel channel, HftMarketDataBus bus) {
        this(channel, bus, 1 << 16);
    }

    public BinaryMarketDataClient(ReadableByteChannel channel, HftMarketDataBus bus,
                                  int bufferBytes) {
        this.channel = channel;
        this.bus = bus;
        this.buffer = ByteBuffer.allocateDirect(bufferBytes).order(ByteOrder.LITTLE_ENDIAN);
        this.readerThread = new Thread(this::readLoop, "binary-md-client");
        readerThread.setDaemon(true);
    }

    /** Retry budget for a publish into a full ring (set before {@link #start()}). */
    public BinaryMarketDataClient withPublishRetryNanos(long nanos) {
        this.publishRetryNanos = Math.max(0, nanos);
        return this;
    }

    public void start() {
        readerThread.start();
    }

    public long messagesDecoded() {
        return messagesDecoded;
    }

    /** Ticks dropped because the bus ring stayed full beyond the retry budget. */
    public long droppedTicks() {
        return droppedTicks;
    }

    /** Frames of templates this client does not consume, skipped by block length. */
    public long skippedMessages() {
        return skippedMessages;
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
                skippedMessages = skippedMessages + 1;
            }
            buffer.position(position + length);
        }
    }

    private void publish(int symbolId, double price, double size, long ts) {
        if (!bus.publish(symbolId, price, size, ts) && !retry(symbolId, price, size, ts)) {
            droppedTicks = droppedTicks + 1;
            gap.set(true);
            return;
        }
        messagesDecoded = messagesDecoded + 1;
    }

    private boolean retry(int symbolId, double price, double size, long ts) {
        long deadline = System.nanoTime() + publishRetryNanos;
        int spins = 0;
        while (System.nanoTime() - deadline < 0) {
            if (++spins < 100) {
                Thread.onSpinWait();
            } else {
                LockSupport.parkNanos(10_000);
            }
            if (bus.publish(symbolId, price, size, ts)) {
                return true;
            }
        }
        return false;
    }
}
