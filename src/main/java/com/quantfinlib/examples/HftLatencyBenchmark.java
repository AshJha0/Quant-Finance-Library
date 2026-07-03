package com.quantfinlib.examples;

import com.quantfinlib.indicators.StreamingIndicators;
import com.quantfinlib.marketdata.HftMarketDataBus;
import com.quantfinlib.util.LatencyRecorder;

import java.util.SplittableRandom;

/**
 * Self-contained benchmark of the HFT hot path with a realistic tick-to-signal
 * workload on the consumer (streaming EMA crossover + RSI per tick).
 *
 * <ol>
 *   <li><b>Throughput</b>: blast ticks through the bus with backpressure spin
 *       and measure sustained ticks/second.</li>
 *   <li><b>Hand-off latency</b>: paced one-in-flight ticks; records
 *       publish-to-strategy latency percentiles ({@code System.nanoTime()} at
 *       publish, measured inside the strategy callback).</li>
 * </ol>
 *
 * <p>Run with: {@code java -cp target/classes com.quantfinlib.examples.HftLatencyBenchmark}
 * (add {@code -XX:+UseZGC -Xms1g -Xmx1g -XX:+AlwaysPreTouch} for production-like jitter).</p>
 */
public final class HftLatencyBenchmark {

    private static final int WARMUP_TICKS = 3_000_000;
    private static final int THROUGHPUT_TICKS = 20_000_000;
    private static final int LATENCY_TICKS = 1_000_000;

    public static void main(String[] args) throws Exception {
        try (HftMarketDataBus bus = new HftMarketDataBus(1 << 16, 16, true)) {
            int eurusd = bus.registerSymbol("EURUSD");

            // Tick-to-signal strategy workload: EMA(12/26) cross + RSI(14).
            StreamingIndicators.Ema fast = new StreamingIndicators.Ema(12);
            StreamingIndicators.Ema slow = new StreamingIndicators.Ema(26);
            StreamingIndicators.Rsi rsi = new StreamingIndicators.Rsi(14);
            LatencyRecorder recorder = new LatencyRecorder();
            long[] signals = new long[1];    // blackhole so the JIT can't elide the work

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

            // ---- Warmup (JIT compilation of the full path) ----
            price = blast(bus, eurusd, rnd, price, WARMUP_TICKS);
            awaitProcessed(bus, WARMUP_TICKS);
            recorder.reset();

            // ---- 1. Throughput: saturating blast ----
            long t0 = System.nanoTime();
            price = blast(bus, eurusd, rnd, price, THROUGHPUT_TICKS);
            awaitProcessed(bus, WARMUP_TICKS + THROUGHPUT_TICKS);
            long elapsed = System.nanoTime() - t0;
            double mticksPerSec = THROUGHPUT_TICKS / (elapsed / 1e9) / 1e6;
            System.out.printf("Throughput: %.1f million ticks/sec (%d ticks in %.2f s, "
                            + "%d backpressure retries, 0 ticks lost)%n",
                    mticksPerSec, THROUGHPUT_TICKS, elapsed / 1e9, bus.ringFullCount());

            // ---- 2. Hand-off latency: one tick in flight at a time ----
            recorder.reset();
            long processedBase = bus.processedCount();
            for (int i = 0; i < LATENCY_TICKS; i++) {
                price = nextPrice(rnd, price);
                while (!bus.publish(eurusd, price, 1_000_000, System.nanoTime())) {
                    Thread.onSpinWait();
                }
                long need = processedBase + i + 1;
                while (bus.processedCount() < need) {
                    Thread.onSpinWait();
                }
            }
            System.out.println("Publish-to-strategy latency: " + recorder.summary());
            System.out.printf("Strategy signals evaluated: %d (last EURUSD price %.5f)%n",
                    signals[0], bus.latestPrice(eurusd));
        }
    }

    private static double blast(HftMarketDataBus bus, int symbolId, SplittableRandom rnd,
                                double price, int ticks) {
        for (int i = 0; i < ticks; i++) {
            price = nextPrice(rnd, price);
            while (!bus.publish(symbolId, price, 1_000_000, System.nanoTime())) {
                Thread.onSpinWait();   // backpressure: never drop
            }
        }
        return price;
    }

    private static double nextPrice(SplittableRandom rnd, double price) {
        return price * (1 + (rnd.nextDouble() - 0.5) * 2e-5);
    }

    private static void awaitProcessed(HftMarketDataBus bus, long target) {
        while (bus.processedCount() < target) {
            Thread.onSpinWait();
        }
    }
}
