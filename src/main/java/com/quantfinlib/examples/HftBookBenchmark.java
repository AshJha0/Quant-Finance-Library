package com.quantfinlib.examples;

import com.quantfinlib.orderbook.HftOrderBook;
import com.quantfinlib.orderbook.Side;
import com.quantfinlib.util.HiccupMonitor;
import com.quantfinlib.util.LatencyRecorder;

import java.util.SplittableRandom;

/**
 * Benchmarks the venue-grade matching engine ({@link HftOrderBook}),
 * completing the fast-lane benchmark family with the venue side:
 *
 * <ol>
 *   <li><b>Passive churn</b>: add/cancel throughput with a deep resting book
 *       — the id-map + pool + bitmap machinery under load;</li>
 *   <li><b>Matching</b>: aggressive flow sweeping resting liquidity —
 *       fills/sec through the FIFO levels;</li>
 *   <li><b>Per-op latency</b>: submit-to-return percentiles for a realistic
 *       70/20/10 add/cancel/aggress mix.</li>
 * </ol>
 *
 * <p>Run: {@code java -Xms512m -Xmx512m -XX:+AlwaysPreTouch -cp target/classes
 * com.quantfinlib.examples.HftBookBenchmark} (add
 * {@code -XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC} to demonstrate
 * that a matching session never needs the collector).</p>
 */
public final class HftBookBenchmark {

    private static final int MIN_TICK = 90_000;   // e.g. $900.00 at $0.01 ticks
    private static final int MAX_TICK = 110_000;
    private static final int WARMUP = 2_000_000;
    private static final int MEASURED = 10_000_000;

    public static void main(String[] args) throws Exception {
        try (HiccupMonitor hiccups = new HiccupMonitor().start()) {
            passiveChurn();
            matching();
            mixedLatency();
            System.out.println(hiccups.summary());
        }
    }

    /** Add/cancel churn against a book holding ~half a million resting orders. */
    private static void passiveChurn() {
        HftOrderBook book = new HftOrderBook(MIN_TICK, MAX_TICK, 1 << 20);
        SplittableRandom rnd = new SplittableRandom(42);
        long[] ring = new long[1 << 16];
        int ringSize = 0;

        // Non-crossing prices: bids low, asks high — pure book maintenance.
        long ops = 0;
        long t0 = 0;
        for (int i = 0; i < WARMUP + MEASURED; i++) {
            if (i == WARMUP) {
                t0 = System.nanoTime();
            }
            if (ringSize == 0 || (ringSize < ring.length && rnd.nextInt(10) < 6)) {
                boolean buy = rnd.nextBoolean();
                int tick = buy ? MIN_TICK + rnd.nextInt(9_000)
                        : MAX_TICK - rnd.nextInt(9_000);
                long id = book.submitLimit(buy ? Side.BUY : Side.SELL, tick,
                        1 + rnd.nextInt(100), i);
                if (id > 0) {
                    ring[ringSize++] = id;
                }
            } else {
                int pick = rnd.nextInt(ringSize);
                book.cancel(ring[pick]);
                ring[pick] = ring[--ringSize];
            }
            if (i >= WARMUP) {
                ops++;
            }
        }
        long elapsed = System.nanoTime() - t0;
        System.out.printf("Passive churn:   %.1f million add/cancel ops/sec "
                        + "(%d resting at end, %d levels band)%n",
                ops / (elapsed / 1e9) / 1e6, book.restingOrders(), MAX_TICK - MIN_TICK + 1);
    }

    /** Aggressive flow sweeping a continuously replenished book. */
    private static void matching() {
        HftOrderBook book = new HftOrderBook(MIN_TICK, MAX_TICK, 1 << 20);
        long[] trades = {0};
        long[] volume = {0};
        book.tradeSink((m, t, tick, qty, ts) -> {
            trades[0]++;
            volume[0] += qty;
        });
        SplittableRandom rnd = new SplittableRandom(7);

        long t0 = 0;
        for (int i = 0; i < WARMUP + MEASURED; i++) {
            if (i == WARMUP) {
                t0 = System.nanoTime();
                trades[0] = 0;
            }
            // Two passive posts per aggressive sweep keeps liquidity flowing.
            int tick = 99_900 + rnd.nextInt(200);
            if ((i % 3) != 0) {
                book.submitLimit((i & 1) == 0 ? Side.BUY : Side.SELL,
                        (i & 1) == 0 ? tick - 60 : tick + 60, 1 + rnd.nextInt(50), i);
            } else {
                book.submitMarket(rnd.nextBoolean() ? Side.BUY : Side.SELL,
                        1 + rnd.nextInt(120), i);
            }
        }
        long elapsed = System.nanoTime() - t0;
        System.out.printf("Matching:        %.1f million ops/sec, %.1f million fills/sec "
                        + "(volume %d)%n",
                MEASURED / (elapsed / 1e9) / 1e6, trades[0] / (elapsed / 1e9) / 1e6, volume[0]);
    }

    /** Latency percentiles for a realistic 70/20/10 add/cancel/aggress mix. */
    private static void mixedLatency() {
        HftOrderBook book = new HftOrderBook(MIN_TICK, MAX_TICK, 1 << 20);
        book.tradeSink((m, t, tick, qty, ts) -> { /* blackhole */ });
        LatencyRecorder recorder = new LatencyRecorder();
        SplittableRandom rnd = new SplittableRandom(1);
        long[] ring = new long[1 << 16];
        int ringSize = 0;

        for (int i = 0; i < WARMUP + MEASURED / 2; i++) {
            if (i == WARMUP) {
                recorder.reset();
            }
            int kind = rnd.nextInt(10);
            long start = System.nanoTime();
            if (kind < 7 || ringSize == 0) {
                int tick = MIN_TICK + rnd.nextInt(MAX_TICK - MIN_TICK + 1);
                long id = book.submitLimit(rnd.nextBoolean() ? Side.BUY : Side.SELL,
                        tick, 1 + rnd.nextInt(100), i);
                if (id > 0 && ringSize < ring.length && book.openQuantity(id) > 0) {
                    ring[ringSize++] = id;
                }
            } else if (kind < 9) {
                int pick = rnd.nextInt(ringSize);
                book.cancel(ring[pick]);
                ring[pick] = ring[--ringSize];
            } else {
                book.submitMarket(rnd.nextBoolean() ? Side.BUY : Side.SELL,
                        1 + rnd.nextInt(200), i);
            }
            recorder.record(System.nanoTime() - start);
        }
        System.out.println("Per-op latency:  " + recorder.summary());
        System.out.printf("Totals: %d orders, %d cancels, %d trades, %d resting%n",
                book.orderCount(), book.cancelCount(), book.tradeCount(), book.restingOrders());
    }
}
