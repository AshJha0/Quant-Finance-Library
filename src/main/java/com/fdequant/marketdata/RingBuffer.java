package com.fdequant.marketdata;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Bounded lock-free single-producer / single-consumer ring buffer for
 * ultra-low-latency event passing: no locks, no allocation on the hot path,
 * power-of-two indexing.
 */
public final class RingBuffer<E> {

    private final AtomicReferenceArray<E> slots;
    private final int mask;
    private final AtomicLong head = new AtomicLong(); // next slot to consume
    private final AtomicLong tail = new AtomicLong(); // next slot to fill

    /** @param capacity rounded up to the next power of two */
    public RingBuffer(int capacity) {
        int cap = Integer.highestOneBit(Math.max(2, capacity - 1)) * 2;
        this.slots = new AtomicReferenceArray<>(cap);
        this.mask = cap - 1;
    }

    /** Producer side. Returns false if the buffer is full (caller decides drop/retry policy). */
    public boolean offer(E e) {
        long t = tail.get();
        if (t - head.get() >= slots.length()) {
            return false;
        }
        slots.set((int) (t & mask), e);
        tail.set(t + 1);
        return true;
    }

    /** Consumer side. Returns null when empty. */
    public E poll() {
        long h = head.get();
        if (h >= tail.get()) {
            return null;
        }
        int idx = (int) (h & mask);
        E e = slots.get(idx);
        slots.set(idx, null);
        head.set(h + 1);
        return e;
    }

    public boolean isEmpty() {
        return head.get() >= tail.get();
    }

    public int capacity() {
        return slots.length();
    }
}
