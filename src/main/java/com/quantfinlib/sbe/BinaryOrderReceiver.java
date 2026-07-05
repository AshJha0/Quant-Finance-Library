package com.quantfinlib.sbe;

import com.quantfinlib.trading.OrderListener;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ReadableByteChannel;

/**
 * Venue side of the binary order-entry pair: decodes {@link OrderFlyweight}
 * frames from a channel and dispatches them to an {@link OrderListener} —
 * same zero-allocation read loop as {@link BinaryMarketDataClient}, with
 * partial-frame handling. An exchange simulator (or matching engine) sits
 * behind the listener.
 */
public final class BinaryOrderReceiver implements AutoCloseable {

    private final ReadableByteChannel channel;
    private final OrderListener listener;
    private final ByteBuffer buffer;
    private final OrderFlyweight order = new OrderFlyweight();
    private final Thread readerThread;
    private volatile boolean running = true;
    private volatile long ordersReceived;
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
                while (buffer.remaining() >= 4) {
                    int position = buffer.position();
                    int type = TradeFlyweight.typeAt(buffer, position);
                    if (type != OrderFlyweight.MESSAGE_TYPE) {
                        throw new IllegalStateException("unknown message type " + type
                                + " on order stream");
                    }
                    if (buffer.remaining() < OrderFlyweight.BLOCK_LENGTH) {
                        break;   // partial frame
                    }
                    order.wrap(buffer, position);
                    listener.onOrder(order.orderId(), order.symbolId(), order.side(),
                            order.quantity(), order.price(), order.timestampNanos());
                    ordersReceived = ordersReceived + 1;
                    buffer.position(position + OrderFlyweight.BLOCK_LENGTH);
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
