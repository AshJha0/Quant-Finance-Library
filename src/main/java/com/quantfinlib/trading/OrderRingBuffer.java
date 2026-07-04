package com.quantfinlib.trading;

import com.quantfinlib.orderbook.Side;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Zero-allocation single-producer / single-consumer ring buffer for order
 * messages — the order-entry mirror of the market-data
 * {@code TickRingBuffer}: preallocated primitive slots, cache-line-padded
 * sequences, acquire/release publication, and producer/consumer sequence
 * caching.
 */
public final class OrderRingBuffer {

    @SuppressWarnings("unused")
    private static final class PaddedSequence extends AtomicLong {
        private volatile long p1, p2, p3, p4, p5, p6, p7;
    }

    private static final Side[] SIDES = {Side.BUY, Side.SELL};

    private final long[] orderIds;
    private final int[] symbolIds;
    private final byte[] sides;
    private final long[] quantities;
    private final double[] prices;
    private final long[] timestamps;
    private final int mask;
    private final int capacity;

    private final PaddedSequence head = new PaddedSequence();
    private final PaddedSequence tail = new PaddedSequence();
    private long cachedHead;
    private long cachedTail;

    public OrderRingBuffer(int requestedCapacity) {
        this.capacity = Integer.highestOneBit(Math.max(2, requestedCapacity - 1)) * 2;
        this.mask = capacity - 1;
        this.orderIds = new long[capacity];
        this.symbolIds = new int[capacity];
        this.sides = new byte[capacity];
        this.quantities = new long[capacity];
        this.prices = new double[capacity];
        this.timestamps = new long[capacity];
    }

    /** Producer side (trading thread). Returns false when full. */
    public boolean publish(long orderId, int symbolId, Side side, long quantity,
                           double price, long timestampNanos) {
        long t = tail.getPlain();
        if (t - cachedHead >= capacity) {
            cachedHead = head.getAcquire();
            if (t - cachedHead >= capacity) {
                return false;
            }
        }
        int i = (int) (t & mask);
        orderIds[i] = orderId;
        symbolIds[i] = symbolId;
        sides[i] = (byte) (side == Side.BUY ? 0 : 1);
        quantities[i] = quantity;
        prices[i] = price;
        timestamps[i] = timestampNanos;
        tail.setRelease(t + 1);
        return true;
    }

    /** Consumer side (venue thread). Drains up to {@code limit} orders into the sink. */
    public int drainTo(OrderListener sink, int limit) {
        long h = head.getPlain();
        if (h >= cachedTail) {
            cachedTail = tail.getAcquire();
            if (h >= cachedTail) {
                return 0;
            }
        }
        int n = (int) Math.min(cachedTail - h, limit);
        for (int k = 0; k < n; k++) {
            int i = (int) (h & mask);
            sink.onOrder(orderIds[i], symbolIds[i], SIDES[sides[i]],
                    quantities[i], prices[i], timestamps[i]);
            h++;
        }
        head.setRelease(h);
        return n;
    }

    public boolean isEmpty() {
        return head.getAcquire() >= tail.getAcquire();
    }

    public int capacity() {
        return capacity;
    }
}
