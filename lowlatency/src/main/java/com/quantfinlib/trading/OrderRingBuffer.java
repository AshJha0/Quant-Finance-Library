package com.quantfinlib.trading;

import com.quantfinlib.marketdata.StridedSequences;
import com.quantfinlib.core.Side;

/**
 * Zero-allocation single-producer / single-consumer ring buffer for
 * order-entry messages, the order-side mirror of
 * {@code marketdata.TickRingBuffer}: preallocated primitive slots, strided
 * sequences on separate cache lines, acquire/release publication and
 * sequence caching.
 *
 * <p>Each slot carries a message type: {@link #NEW}, {@link #CANCEL},
 * {@link #REPLACE} or {@link #CANCEL_ALL}. The consumer dispatches each to
 * the matching {@link OrderListener} method.</p>
 */
public final class OrderRingBuffer {

    public static final byte NEW = 0;
    public static final byte CANCEL = 1;
    public static final byte REPLACE = 2;
    public static final byte CANCEL_ALL = 3;

    private static final int HEAD = 0;
    private static final int TAIL = 1;
    private static final int CACHED_HEAD = 2;
    private static final int CACHED_TAIL = 3;

    private static final Side[] SIDES = {Side.BUY, Side.SELL};

    private final byte[] types;
    private final byte[] flags;
    private final long[] orderIds;
    private final int[] symbolIds;
    private final byte[] sides;
    private final long[] quantities;
    private final double[] prices;
    private final long[] timestamps;
    private final int mask;
    private final int capacity;
    private final StridedSequences seq = new StridedSequences(4);

    /** @param requestedCapacity rounded up to the next power of two */
    public OrderRingBuffer(int requestedCapacity) {
        this.capacity = Integer.highestOneBit(Math.max(2, requestedCapacity - 1)) * 2;
        this.mask = capacity - 1;
        this.types = new byte[capacity];
        this.flags = new byte[capacity];
        this.orderIds = new long[capacity];
        this.symbolIds = new int[capacity];
        this.sides = new byte[capacity];
        this.quantities = new long[capacity];
        this.prices = new double[capacity];
        this.timestamps = new long[capacity];
    }

    /** Producer side: publishes a new order with no flags. Returns false when full. */
    public boolean publish(long orderId, int symbolId, Side side, long quantity,
                           double price, long timestampNanos) {
        return publish(NEW, OrderListener.FLAG_NONE, orderId, symbolId, side, quantity, price,
                timestampNanos);
    }

    /**
     * Producer side (single trading thread). Publishes one message of the
     * given type; unused fields may be zero (for {@link #CANCEL_ALL},
     * {@code symbolId} may be {@link OrderListener#ALL_SYMBOLS}). Returns
     * false when the ring is full.
     */
    public boolean publish(byte type, int orderFlags, long orderId, int symbolId, Side side,
                           long quantity, double price, long timestampNanos) {
        long t = seq.getPlain(TAIL);
        if (t - seq.getPlain(CACHED_HEAD) >= capacity) {
            seq.setPlain(CACHED_HEAD, seq.getAcquire(HEAD));
            if (t - seq.getPlain(CACHED_HEAD) >= capacity) {
                return false;
            }
        }
        int i = (int) (t & mask);
        types[i] = type;
        flags[i] = (byte) orderFlags;
        orderIds[i] = orderId;
        symbolIds[i] = symbolId;
        sides[i] = (byte) (side == Side.SELL ? 1 : 0);
        quantities[i] = quantity;
        prices[i] = price;
        timestamps[i] = timestampNanos;
        seq.setRelease(TAIL, t + 1);
        return true;
    }

    /**
     * Consumer side (venue thread). Drains up to {@code limit} messages into
     * the sink. If the sink throws, the message that threw counts as
     * consumed (the head moves past it) before the exception propagates.
     */
    public int drainTo(OrderListener sink, int limit) {
        long h = seq.getPlain(HEAD);
        long cachedTail = seq.getPlain(CACHED_TAIL);
        if (h >= cachedTail) {
            cachedTail = seq.getAcquire(TAIL);
            seq.setPlain(CACHED_TAIL, cachedTail);
            if (h >= cachedTail) {
                return 0;
            }
        }
        int n = (int) Math.min(cachedTail - h, limit);
        long start = h;
        try {
            for (int k = 0; k < n; k++) {
                int i = (int) (h & mask);
                h++;
                switch (types[i]) {
                    case NEW -> sink.onNewOrder(orderIds[i], symbolIds[i], SIDES[sides[i]],
                            quantities[i], prices[i], flags[i], timestamps[i]);
                    case CANCEL -> sink.onCancel(orderIds[i], symbolIds[i], timestamps[i]);
                    case REPLACE -> sink.onReplace(orderIds[i], symbolIds[i], SIDES[sides[i]],
                            quantities[i], prices[i], timestamps[i]);
                    case CANCEL_ALL -> sink.onCancelAll(symbolIds[i], timestamps[i]);
                    default -> throw new IllegalStateException("bad message type " + types[i]);
                }
            }
        } finally {
            seq.setRelease(HEAD, h);
        }
        return (int) (h - start);
    }

    public boolean isEmpty() {
        return seq.getAcquire(HEAD) >= seq.getAcquire(TAIL);
    }

    public int capacity() {
        return capacity;
    }
}
