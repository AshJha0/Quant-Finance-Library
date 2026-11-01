package com.quantfinlib.marketdata;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * A small set of {@code long} counters laid out in one {@code long[]} with a
 * 128-byte stride, so every counter owns its cache line (and the adjacent
 * line that Intel's spatial prefetcher fetches in pairs) whatever the JVM
 * does with field order.
 *
 * <p>Why an array: padding with extra fields relies on HotSpot keeping
 * declaration order, which the JVM specification does not promise, and
 * padding an {@code AtomicLong} subclass only pads after the value, so the
 * object header and value can share a line with whatever was allocated just
 * before. Array elements are contiguous, so slot {@code k} sits
 * {@code (k + 1) * 16} longs from the start; the first 16 longs are leading
 * padding against the array header and the trailing 16 against whatever is
 * allocated next.</p>
 *
 * <p>Access modes follow {@link VarHandle}: plain for the owning thread,
 * opaque for observability counters read by monitoring threads (no
 * ordering, but a reader cannot see a stale value forever), acquire/release
 * for sequences that publish ring slots.</p>
 */
public final class StridedSequences {

    /** Longs per slot: 16 x 8 bytes = 128 bytes. */
    public static final int STRIDE = 16;

    private static final VarHandle LONGS = MethodHandles.arrayElementVarHandle(long[].class);

    private final long[] values;

    /** @param count number of independent counters, each on its own line pair */
    public StridedSequences(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("count must be > 0");
        }
        this.values = new long[(count + 2) * STRIDE];
    }

    private static int index(int k) {
        return (k + 1) * STRIDE;
    }

    public long getPlain(int k) {
        return values[index(k)];
    }

    public void setPlain(int k, long v) {
        values[index(k)] = v;
    }

    public long getAcquire(int k) {
        return (long) LONGS.getAcquire(values, index(k));
    }

    public void setRelease(int k, long v) {
        LONGS.setRelease(values, index(k), v);
    }

    public long getOpaque(int k) {
        return (long) LONGS.getOpaque(values, index(k));
    }

    public void setOpaque(int k, long v) {
        LONGS.setOpaque(values, index(k), v);
    }

    /** Single-writer increment, published in opaque mode for readers. */
    public void incrementOpaque(int k) {
        int i = index(k);
        LONGS.setOpaque(values, i, values[i] + 1);
    }

    /** Single-writer add, published in opaque mode for readers. */
    public void addOpaque(int k, long delta) {
        int i = index(k);
        LONGS.setOpaque(values, i, values[i] + delta);
    }

    /** Atomic add, for counters with more than one writer. */
    public long getAndAdd(int k, long delta) {
        return (long) LONGS.getAndAdd(values, index(k), delta);
    }
}
