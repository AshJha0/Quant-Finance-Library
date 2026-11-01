package com.quantfinlib.sbe;

import com.quantfinlib.orderbook.Side;
import com.quantfinlib.trading.OrderListener;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ReadableByteChannel;

/**
 * Venue side of the binary order channel: decodes {@link OrderFlyweight}
 * frames and hands each order to an {@link OrderListener}. Framing follows
 * {@link MessageHeader} (unknown templates skipped by block length, a
 * foreign schema stops the reader with {@link #failureReason()}), and
 * schema-version-1 order frames are still decoded. Prices reach the
 * listener as doubles, NaN for market orders.
 */
public final class BinaryOrderReceiver implements AutoCloseable {

    private final ReadableByteChannel channel;
    private final OrderListener listener;
    private final ByteBuffer buffer;
    private final OrderFlyweight order = new OrderFlyweight();
    private final int[] kind = new int[1];
    private final Thread readerThread;
    private volatile boolean running = true;
    private volatile long ordersReceived;
    private volatile long skippedMessages;
    private volatile String failureReason;

    public BinaryOrderReceiver(ReadableByteChannel channel, OrderListener listener) {
        this.channel = channel;
        this.listener = listener;
        this.buffer = ByteBuffer.allocateDirect(1 << 14).order(ByteOrder.LITTLE_ENDIAN);
        this.readerThread = new Thread(this::readLoop, "binary-order-receiver");
        readerThread.setDaemon(true);
    }

    public void start() {
        readerThread.start();
    }

    public long ordersReceived() {
        return ordersReceived;
    }

    /** Frames of other templates, skipped by block length. */
    public long skippedMessages() {
        return skippedMessages;
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
                        ordersReceived = ordersReceived + 1;
                    } else if (k == -2) {
                        listener.onOrder(buffer.getLong(position + SbeFrames.V1_ORDER_ID),
                                buffer.getInt(position + SbeFrames.V1_ORDER_SYMBOL),
                                buffer.get(position + SbeFrames.V1_ORDER_SIDE) == 0
                                        ? Side.BUY : Side.SELL,
                                buffer.getLong(position + SbeFrames.V1_ORDER_QTY),
                                buffer.getDouble(position + SbeFrames.V1_ORDER_PRICE),
                                buffer.getLong(position + SbeFrames.V1_ORDER_TS));
                        ordersReceived = ordersReceived + 1;
                    } else {
                        skippedMessages = skippedMessages + 1;
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
