package com.quantfinlib.marketdata;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Zero-allocation single-producer / single-consumer ring buffer for market
 * data ticks, in the style of the LMAX Disruptor:
 *
 * <ul>
 *   <li><b>Preallocated primitive slots</b> — ticks live in parallel
 *       {@code int[]/double[]/long[]} arrays; nothing is allocated per tick
 *       on either side, so the steady-state GC load is zero.</li>
 *   <li><b>Cache-line-padded sequences</b> — head and tail counters are padded
 *       to avoid false sharing between the producer and consumer cores.</li>
 *   <li><b>Acquire/release ordering</b> — slot writes are published with a
 *       single release store of the tail; no CAS, no locks on the hot path.</li>
 *   <li><b>Sequence caching</b> — each side caches the other's sequence and
 *       only re-reads the volatile counter when it appears blocked, removing
 *       most cross-core traffic.</li>
 * </ul>
 */
public final class TickRingBuffer {

    /** Right-padded sequence counter (own cache line for the value). */
    @SuppressWarnings("unused")
    private static final class PaddedSequence extends AtomicLong {
        private volatile long p1, p2, p3, p4, p5, p6, p7;
    }

    private final int[] symbolIds;
    private final double[] prices;
    private final double[] sizes;
    private final long[] timestamps;
    private final int mask;
    private final int capacity;

    private final PaddedSequence head = new PaddedSequence(); // next slot to consume
    private final PaddedSequence tail = new PaddedSequence(); // next slot to fill
    private long cachedHead;   // producer-local view of head
    private long cachedTail;   // consumer-local view of tail

    /** @param requestedCapacity rounded up to the next power of two */
    public TickRingBuffer(int requestedCapacity) {
        this.capacity = Integer.highestOneBit(Math.max(2, requestedCapacity - 1)) * 2;
        this.mask = capacity - 1;
        this.symbolIds = new int[capacity];
        this.prices = new double[capacity];
        this.sizes = new double[capacity];
        this.timestamps = new long[capacity];
    }

    /**
     * Producer side; single producer thread only. Returns false when full —
     * the caller chooses the backpressure policy (spin, drop, or count).
     */
    public boolean publish(int symbolId, double price, double size, long timestampNanos) {
        long t = tail.getPlain();
        if (t - cachedHead >= capacity) {
            cachedHead = head.getAcquire();
            if (t - cachedHead >= capacity) {
                return false;
            }
        }
        int i = (int) (t & mask);
        symbolIds[i] = symbolId;
        prices[i] = price;
        sizes[i] = size;
        timestamps[i] = timestampNanos;
        tail.setRelease(t + 1);
        return true;
    }

    /**
     * Consumer side; single consumer thread only. Drains up to {@code limit}
     * ticks into the sink and returns how many were delivered.
     */
    public int drainTo(TickListener sink, int limit) {
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
            sink.onTick(symbolIds[i], prices[i], sizes[i], timestamps[i]);
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
