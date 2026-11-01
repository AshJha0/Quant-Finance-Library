package com.quantfinlib.marketdata;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Bounded lock-free single-producer / single-consumer ring of object
 * references, built like {@link TickRingBuffer}: head and tail on separate
 * cache lines ({@link StridedSequences}), slot contents published by a
 * release store of the tail and observed after an acquire load, and each
 * side caching the other's sequence so the shared line is touched only when
 * the ring looks full or empty. No locks; no allocation by the ring itself
 * (the caller's objects are its own business).
 *
 * <p>Exactly one producer thread may call {@link #offer} and exactly one
 * consumer thread {@link #poll}.</p>
 */
public final class RingBuffer<E> {

    private static final VarHandle SLOTS = MethodHandles.arrayElementVarHandle(Object[].class);

    private static final int HEAD = 0;
    private static final int TAIL = 1;
    private static final int CACHED_HEAD = 2;
    private static final int CACHED_TAIL = 3;

    private final Object[] slots;
    private final int mask;
    private final StridedSequences seq = new StridedSequences(4);

    /** @param capacity rounded up to the next power of two */
    public RingBuffer(int capacity) {
        int cap = Integer.highestOneBit(Math.max(2, capacity - 1)) * 2;
        this.slots = new Object[cap];
        this.mask = cap - 1;
    }

    /** Producer side. Returns false if the buffer is full (caller decides drop/retry policy). */
    public boolean offer(E e) {
        long t = seq.getPlain(TAIL);
        if (t - seq.getPlain(CACHED_HEAD) >= slots.length) {
            seq.setPlain(CACHED_HEAD, seq.getAcquire(HEAD));
            if (t - seq.getPlain(CACHED_HEAD) >= slots.length) {
                return false;
            }
        }
        slots[(int) (t & mask)] = e;
        seq.setRelease(TAIL, t + 1);
        return true;
    }

    /** Consumer side. Returns null when empty. */
    @SuppressWarnings("unchecked")
    public E poll() {
        long h = seq.getPlain(HEAD);
        if (h >= seq.getPlain(CACHED_TAIL)) {
            seq.setPlain(CACHED_TAIL, seq.getAcquire(TAIL));
            if (h >= seq.getPlain(CACHED_TAIL)) {
                return null;
            }
        }
        int idx = (int) (h & mask);
        E e = (E) slots[idx];
        // Plain null-out: the producer only rewrites this slot after it sees
        // the head move past it (release below, acquire on its side).
        SLOTS.set(slots, idx, null);
        seq.setRelease(HEAD, h + 1);
        return e;
    }

    public boolean isEmpty() {
        return seq.getAcquire(HEAD) >= seq.getAcquire(TAIL);
    }

    public int capacity() {
        return slots.length;
    }
}
