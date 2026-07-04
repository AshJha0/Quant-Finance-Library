package com.quantfinlib.marketdata;

import com.quantfinlib.orderbook.Side;
import com.quantfinlib.trading.OrderRingBuffer;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrent SPSC stress for all three ring buffers: 2M items pushed through
 * small rings with randomized producer bursts and consumer batch sizes, so
 * wrap-around, full-ring backpressure and empty-ring polling are all hit
 * millions of times. Asserts strict FIFO with no loss or duplication.
 */
class RingBufferStressTest {

    private static final int ITEMS = 2_000_000;

    @Test
    void tickRingSurvivesConcurrentStress() throws Exception {
        TickRingBuffer ring = new TickRingBuffer(256);
        AtomicBoolean failed = new AtomicBoolean();

        Thread producer = new Thread(() -> {
            SplittableRandom rnd = new SplittableRandom(1);
            for (int i = 0; i < ITEMS; i++) {
                while (!ring.publish(i & 7, i, 1, i)) {
                    Thread.onSpinWait();
                }
                if ((i & 0xFFF) == 0 && rnd.nextInt(4) == 0) {
                    Thread.yield();   // jitter the producer
                }
            }
        });
        producer.start();

        SplittableRandom rnd = new SplittableRandom(2);
        long[] next = {0};
        while (next[0] < ITEMS) {
            ring.drainTo((symbolId, price, size, ts) -> {
                if ((long) price != next[0] || ts != next[0] || symbolId != (next[0] & 7)) {
                    failed.set(true);
                }
                next[0]++;
            }, 1 + rnd.nextInt(64));
        }
        producer.join(10_000);
        assertEquals(ITEMS, next[0]);
        assertTrue(ring.isEmpty());
        assertTrue(!failed.get(), "FIFO violated");
    }

    @Test
    void orderRingSurvivesConcurrentStress() throws Exception {
        OrderRingBuffer ring = new OrderRingBuffer(128);
        AtomicBoolean failed = new AtomicBoolean();

        Thread producer = new Thread(() -> {
            for (int i = 0; i < ITEMS; i++) {
                Side side = (i & 1) == 0 ? Side.BUY : Side.SELL;
                while (!ring.publish(i, i & 3, side, i, 100 + i, i)) {
                    Thread.onSpinWait();
                }
            }
        });
        producer.start();

        SplittableRandom rnd = new SplittableRandom(3);
        long[] next = {0};
        while (next[0] < ITEMS) {
            ring.drainTo((orderId, symbolId, side, qty, price, ts) -> {
                Side expectedSide = (next[0] & 1) == 0 ? Side.BUY : Side.SELL;
                if (orderId != next[0] || qty != next[0] || side != expectedSide
                        || symbolId != (next[0] & 3)) {
                    failed.set(true);
                }
                next[0]++;
            }, 1 + rnd.nextInt(32));
        }
        producer.join(10_000);
        assertEquals(ITEMS, next[0]);
        assertTrue(!failed.get(), "FIFO violated");
    }

    @Test
    void objectRingSurvivesConcurrentStress() throws Exception {
        RingBuffer<Integer> ring = new RingBuffer<>(64);
        Thread producer = new Thread(() -> {
            for (int i = 0; i < ITEMS / 4; i++) {
                while (!ring.offer(i)) {
                    Thread.onSpinWait();
                }
            }
        });
        producer.start();

        int next = 0;
        while (next < ITEMS / 4) {
            Integer v = ring.poll();
            if (v != null) {
                assertEquals(next, v);
                next++;
            }
        }
        producer.join(10_000);
        assertEquals(ITEMS / 4, next);
        assertTrue(ring.isEmpty());
    }
}
