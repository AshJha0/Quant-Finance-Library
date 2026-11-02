package com.quantfinlib.sbe;

import com.quantfinlib.marketdata.StridedSequences;
import com.quantfinlib.core.Side;
import com.quantfinlib.trading.OrderListener;
import com.quantfinlib.util.ThreadPlacement;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ReadableByteChannel;
import java.util.concurrent.ThreadFactory;

/**
 * Venue side of the binary order channel: decodes {@link OrderFlyweight}
 * frames and hands each order to an {@link OrderListener}. Framing follows
 * {@link MessageHeader} (unknown templates skipped by block length, a
 * foreign schema stops the reader with {@link #failureReason()}), and
 * schema-version-1 order frames are still decoded. Prices reach the
 * listener as doubles, NaN for market orders.
 */
public final class BinaryOrderReceiver implements AutoCloseable {

    /** Private monitor for lifecycle and registration; never exposed to callers. */
    private final Object lock = new Object();

    private final ReadableByteChannel channel;
    private final OrderListener listener;
    private final ByteBuffer buffer;
    private final OrderFlyweight order = new OrderFlyweight();
    private final int[] kind = new int[1];
    private Thread readerThread;
    private ThreadFactory threadFactory = ThreadPlacement.daemon("binary-order-receiver");
    private volatile boolean running = true;
    // Reader-thread counters: single writer, opaque increments on padded slots.
    private static final int RECEIVED = 0;
    private static final int SKIPPED = 1;
    private final StridedSequences counters = new StridedSequences(2);
    private volatile String failureReason;

    public BinaryOrderReceiver(ReadableByteChannel channel, OrderListener listener) {
        this.channel = channel;
        this.listener = listener;
        this.buffer = ByteBuffer.allocateDirect(1 << 14).order(ByteOrder.LITTLE_ENDIAN);
    }

    /**
     * Replaces the factory that creates the reader thread (naming, CPU
     * placement; see {@link ThreadPlacement}). Call before {@link #start()}.
     *
     * @param factory thread factory
     * @return this receiver
     */
    public BinaryOrderReceiver threadFactory(ThreadFactory factory) {
        synchronized (lock) {
            if (factory == null) {
                throw new IllegalArgumentException("factory is required");
            }
            this.threadFactory = factory;
            return this;
        }
    }

    public void start() {
        synchronized (lock) {
            if (readerThread != null) {
                return;
            }
            readerThread = threadFactory.newThread(this::readLoop);
            readerThread.start();
        }
    }

    public long ordersReceived() {
        return counters.getOpaque(RECEIVED);
    }

    /** Frames of other templates, skipped by block length. */
    public long skippedMessages() {
        return counters.getOpaque(SKIPPED);
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
                    return;
                }
                buffer.flip();
                while (true) {
                    int position = buffer.position();
                    int length = SbeFrames.frameLength(buffer, position, kind);
                    if (length == 0) {
                        break;   // partial frame
                    }
                    int k = kind[0];
                    if (k == OrderFlyweight.TEMPLATE_ID) {
                        order.wrap(buffer, position);
                        listener.onOrder(order.orderId(), order.symbolId(), order.side(),
                                order.quantity(), order.price(), order.timestampNanos());
                        counters.incrementOpaque(RECEIVED);
                    } else if (k == -2) {
                        listener.onOrder(buffer.getLong(position + SbeFrames.V1_ORDER_ID),
                                buffer.getInt(position + SbeFrames.V1_ORDER_SYMBOL),
                                buffer.get(position + SbeFrames.V1_ORDER_SIDE) == 0
                                        ? Side.BUY : Side.SELL,
                                buffer.getLong(position + SbeFrames.V1_ORDER_QTY),
                                buffer.getDouble(position + SbeFrames.V1_ORDER_PRICE),
                                buffer.getLong(position + SbeFrames.V1_ORDER_TS));
                        counters.incrementOpaque(RECEIVED);
                    } else {
                        counters.incrementOpaque(SKIPPED);
                    }
                    buffer.position(position + length);
                }
                buffer.compact();
            }
        } catch (IOException e) {
            if (running) {
                failureReason = "read failed: " + e.getMessage();
            }
        } catch (RuntimeException e) {
            failureReason = e.getMessage();
        }
    }
}
