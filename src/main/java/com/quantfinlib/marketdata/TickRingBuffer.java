package com.quantfinlib.marketdata;

/**
 * Zero-allocation single-producer / single-consumer ring buffer for market
 * data ticks, in the style of the LMAX Disruptor (Thompson et al., 2011):
 *
 * <ul>
 *   <li><b>Preallocated primitive slots</b>: ticks live in parallel
 *       {@code int[]/double[]/long[]} arrays; nothing is allocated per tick
 *       on either side.</li>
 *   <li><b>Strided sequences</b>: head, tail and the two local sequence
 *       caches live in one {@link StridedSequences} array, 128 bytes apart,
 *       so producer and consumer never write the same cache line and the
 *       layout does not depend on JVM field ordering.</li>
 *   <li><b>Acquire/release ordering</b>: slot writes are published with a
 *       single release store of the tail; no CAS, no locks.</li>
 *   <li><b>Sequence caching</b>: each side caches the other's sequence and
 *       only re-reads it when it appears blocked.</li>
 * </ul>
 *
 * <p>Sequences are 64-bit and never wrap in practice (2^63 ticks); slot
 * indices wrap with {@code sequence & mask}.</p>
 */
public final class TickRingBuffer {

    private static final int HEAD = 0;         // next slot to consume (consumer writes)
    private static final int TAIL = 1;         // next slot to fill (producer writes)
    private static final int CACHED_HEAD = 2;  // producer-local view of head
    private static final int CACHED_TAIL = 3;  // consumer-local view of tail

    private final int[] symbolIds;
    private final double[] prices;
    private final double[] sizes;
    private final long[] timestamps;
    private final int mask;
    private final int capacity;
    private final StridedSequences seq = new StridedSequences(4);

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
     * Producer side; single producer thread only. Returns false when full;
     * the caller chooses the backpressure policy (spin, drop, or count).
     */
    public boolean publish(int symbolId, double price, double size, long timestampNanos) {
        long t = seq.getPlain(TAIL);
        if (t - seq.getPlain(CACHED_HEAD) >= capacity) {
            seq.setPlain(CACHED_HEAD, seq.getAcquire(HEAD));
            if (t - seq.getPlain(CACHED_HEAD) >= capacity) {
                return false;
            }
        }
        int i = (int) (t & mask);
        symbolIds[i] = symbolId;
        prices[i] = price;
        sizes[i] = size;
        timestamps[i] = timestampNanos;
        seq.setRelease(TAIL, t + 1);
        return true;
    }

    /**
     * Consumer side; single consumer thread only. Drains up to {@code limit}
     * ticks into the sink and returns how many were consumed. If the sink
     * throws, the head is advanced past the tick that threw (it counts as
     * consumed) before the exception propagates, so a poison tick cannot be
     * redelivered forever.
     */
    public int drainTo(TickListener sink, int limit) {
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
                sink.onTick(symbolIds[i], prices[i], sizes[i], timestamps[i]);
            }
        } finally {
            seq.setRelease(HEAD, h);
        }
        return (int) (h - start);
    }

    public boolean isEmpty() {
        return seq.getAcquire(HEAD) >= seq.getAcquire(TAIL);
    }

    /** Ticks published and not yet consumed (approximate when read concurrently). */
    public long size() {
        return seq.getAcquire(TAIL) - seq.getAcquire(HEAD);
    }

    public int capacity() {
        return capacity;
    }
}
