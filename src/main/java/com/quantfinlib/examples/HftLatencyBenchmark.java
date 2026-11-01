package com.quantfinlib.examples;

import com.quantfinlib.indicators.StreamingIndicators;
import com.quantfinlib.marketdata.HftMarketDataBus;
import com.quantfinlib.util.HiccupMonitor;
import com.quantfinlib.util.LatencyRecorder;

import java.util.SplittableRandom;

/**
 * Benchmark of the market-data hot path with a tick-to-signal workload on
 * the consumer (streaming EMA crossover and RSI per tick).
 *
 * <ol>
 *   <li><b>Throughput</b>: blast ticks with backpressure spin; reports
 *       ticks per second and the measured loss (published minus processed,
 *       counted, not assumed).</li>
 *   <li><b>Closed-loop hand-off</b>: one tick in flight at a time. This
 *       understates tails: while the consumer stalls, no new tick is issued,
 *       so the stall is charged to one sample instead of every tick that
 *       would have arrived (coordinated omission).</li>
 *   <li><b>Open-loop hand-off</b>: ticks scheduled at a fixed rate; each is
 *       stamped with its intended send time {@code t0 + i * period} and the
 *       consumer records {@code now - intended}. A stall delays every tick
 *       scheduled behind it, and each of those delays is recorded, which is
 *       the coordinated-omission correction described by Tene (2013,
 *       "How NOT to measure latency").</li>
 * </ol>
 *
 * <p>Every summary prints the clock step: on Windows {@code nanoTime} moves
 * in 100 ns steps, so sub-microsecond values there are a count of clock
 * ticks, not a measurement to the nanosecond.</p>
 *
 * <p>Run: {@code java -cp target/classes com.quantfinlib.examples.HftLatencyBenchmark [ratePerSec]}
 * (add {@code -XX:+UseZGC -Xms1g -Xmx1g -XX:+AlwaysPreTouch} to reduce jitter).</p>
 */
public final class HftLatencyBenchmark {

    private static final int WARMUP_TICKS = 3_000_000;
    private static final int THROUGHPUT_TICKS = 20_000_000;
    private static final int LATENCY_TICKS = 1_000_000;

    public static void main(String[] args) throws Exception {
        long ratePerSec = args.length > 0 ? Long.parseLong(args[0]) : 500_000;
        try (HiccupMonitor hiccups = new HiccupMonitor().start();
             HftMarketDataBus bus = new HftMarketDataBus(1 << 16, 16, true)) {
            int eurusd = bus.registerSymbol("EURUSD");

            StreamingIndicators.Ema fast = new StreamingIndicators.Ema(12);
            StreamingIndicators.Ema slow = new StreamingIndicators.Ema(26);
            StreamingIndicators.Rsi rsi = new StreamingIndicators.Rsi(14);
            LatencyRecorder recorder = new LatencyRecorder();
            long[] signals = new long[1];    // blackhole so the JIT cannot drop the work

            bus.subscribe(eurusd, (id, price, size, ts) -> {
                double f = fast.update(price);
                double s = slow.update(price);
                double r = rsi.update(price);
                if (f > s && r < 70) {
                    signals[0]++;
                }
                recorder.record(System.nanoTime() - ts);
            });
            bus.start();

            SplittableRandom rnd = new SplittableRandom(42);
            double price = 1.0850;
            long published = 0;

            price = blast(bus, eurusd, rnd, price, WARMUP_TICKS);
            published += WARMUP_TICKS;
            awaitProcessed(bus, published);

            // ---- 1. Throughput ----
            long t0 = System.nanoTime();
            price = blast(bus, eurusd, rnd, price, THROUGHPUT_TICKS);
            published += THROUGHPUT_TICKS;
            awaitProcessed(bus, published);
            long elapsed = System.nanoTime() - t0;
            System.out.printf("Throughput: %.1f million ticks/sec (%d ticks in %.2f s, "
                            + "%d backpressure retries, %d ticks lost)%n",
                    THROUGHPUT_TICKS / (elapsed / 1e9) / 1e6, THROUGHPUT_TICKS, elapsed / 1e9,
                    bus.ringFullCount(), published - bus.processedCount());

            // ---- 2. Closed loop ----
            recorder.reset();
            for (int i = 0; i < LATENCY_TICKS; i++) {
                price = nextPrice(rnd, price);
                while (!bus.publish(eurusd, price, 1_000_000, System.nanoTime())) {
                    Thread.onSpinWait();
                }
                published++;
                awaitProcessed(bus, published);
            }
            System.out.println("Closed-loop publish-to-strategy: " + recorder.summary());

            // ---- 3. Open loop at a fixed rate, intended-start timestamps ----
            recorder.reset();
            long period = 1_000_000_000L / ratePerSec;
            long start = System.nanoTime() + 1_000_000;
            for (int i = 0; i < LATENCY_TICKS; i++) {
                long intended = start + i * period;
                while (System.nanoTime() < intended) {
                    Thread.onSpinWait();
                }
                price = nextPrice(rnd, price);
                while (!bus.publish(eurusd, price, 1_000_000, intended)) {
                    Thread.onSpinWait();
                }
                published++;
            }
            awaitProcessed(bus, published);
            System.out.printf("Open-loop at %,d ticks/s (intended-start): %s%n",
                    ratePerSec, recorder.summary());
            System.out.printf("Signals evaluated: %d (last price %.5f), lost overall: %d%n",
                    signals[0], bus.latestPrice(eurusd), published - bus.processedCount());
            System.out.println(hiccups.summary());
        }
    }

    private static double blast(HftMarketDataBus bus, int symbolId, SplittableRandom rnd,
                                double price, int ticks) {
        for (int i = 0; i < ticks; i++) {
            price = nextPrice(rnd, price);
            while (!bus.publish(symbolId, price, 1_000_000, System.nanoTime())) {
                Thread.onSpinWait();
            }
        }
        return price;
    }

    private static double nextPrice(SplittableRandom rnd, double price) {
        return price * (1 + (rnd.nextDouble() - 0.5) * 2e-5);
    }

    private static void awaitProcessed(HftMarketDataBus bus, long target) {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (bus.processedCount() < target && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }
}
