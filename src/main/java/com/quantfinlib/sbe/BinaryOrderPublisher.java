package com.quantfinlib.sbe;

import com.quantfinlib.orderbook.Side;
import com.quantfinlib.trading.OrderListener;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.WritableByteChannel;

/**
 * Binary order-entry adapter: attach to {@code HftOrderGateway} as its
 * {@link OrderListener} and every accepted order is encoded as an
 * {@link OrderFlyweight} and written to the venue channel — the binary
 * counterpart of {@code fix.FixSession#sendNewOrderSingle}, with zero
 * allocation and zero string formatting per order.
 *
 * <p>Runs on the gateway's single venue thread, so writes need no lock; the
 * reusable direct buffer is written fully (looping short writes) before the
 * next order is encoded.</p>
 */
public final class BinaryOrderPublisher implements OrderListener, AutoCloseable {

    private final WritableByteChannel channel;
    private final ByteBuffer buffer =
            ByteBuffer.allocateDirect(OrderFlyweight.BLOCK_LENGTH).order(ByteOrder.LITTLE_ENDIAN);
    private final OrderFlyweight order = new OrderFlyweight();
    private long ordersSent;

    public BinaryOrderPublisher(WritableByteChannel channel) {
        this.channel = channel;
        order.wrap(buffer, 0);
    }

    @Override
    public void onOrder(long orderId, int symbolId, Side side, long quantity,
                        double price, long timestampNanos) {
        order.encode(orderId, symbolId, side, quantity, price, timestampNanos);
        buffer.position(0).limit(OrderFlyweight.BLOCK_LENGTH);
        try {
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("order publish failed for " + orderId, e);
        }
        ordersSent++;
    }

    public long ordersSent() {
        return ordersSent;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
