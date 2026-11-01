package com.quantfinlib.bench;

import com.quantfinlib.indicators.StreamingIndicators;
import com.quantfinlib.marketdata.TickListener;
import com.quantfinlib.marketdata.TickRingBuffer;
import com.quantfinlib.orderbook.Side;
import com.quantfinlib.pricing.BlackScholes;
import com.quantfinlib.pricing.BlackScholes.OptionType;
import com.quantfinlib.trading.HftRiskGate;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * JMH microbenchmarks for the latency-critical primitives — statistically
 * rigorous companions to the hand-rolled end-to-end benchmarks
 * ({@code HftLatencyBenchmark}, {@code HftOrderBenchmark}).
 *
 * <p>Run: {@code mvn test-compile exec:java
 * -Dexec.mainClass=com.quantfinlib.bench.BenchRunner -Dexec.classpathScope=test
 * -Dexec.args=CoreBenchmarks}</p>
 *
 * <p>Three forks so JIT and layout luck show up as between-fork variance
 * instead of hiding in one run. {@code riskGateCheck} uses one symbol and
 * a constant price (branch predictor and cache in the best case);
 * {@code riskGateCheckRandomSymbols} draws the symbol, side and price from a
 * pre-generated random table over 1,024 symbols, which is the more realistic
 * figure for a book of many instruments.</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Fork(value = 3, jvmArgsAppend = {"-Xms1g", "-Xmx1g", "-XX:+UseZGC", "-XX:+UseCompactObjectHeaders",
        "-XX:+AlwaysPreTouch", "-XX:+PerfDisableSharedMem"})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class CoreBenchmarks {

    private static final TickListener SINK = (id, price, size, ts) -> {
    };

    private HftRiskGate gate;
    private TickRingBuffer ring;
    private StreamingIndicators.Ema ema;
    private long tick;
    private double price;
    private HftRiskGate wideGate;
    private int[] randomSymbols;
    private Side[] randomSides;
    private double[] randomPrices;
    private int cursor;

    @Setup
    public void setup() {
        gate = new HftRiskGate(16)
                .maxOrderQuantity(1_000_000)
                .maxOrderNotional(1e12)
                .maxPositionQuantity(Long.MAX_VALUE / 4)
                .priceCollarPct(0.10);
        gate.setReferencePrice(0, 1.0850);
        ring = new TickRingBuffer(1 << 12);
        ema = new StreamingIndicators.Ema(20);
        price = 1.0850;

        wideGate = new HftRiskGate(1_024)
                .maxOrderQuantity(1_000_000)
                .maxOrderNotional(1e12)
                .maxPositionQuantity(10_000_000)
                .priceCollarPct(0.005);
        java.util.SplittableRandom rnd = new java.util.SplittableRandom(11);
        randomSymbols = new int[1 << 16];
        randomSides = new Side[1 << 16];
        randomPrices = new double[1 << 16];
        for (int s = 0; s < 1_024; s++) {
            wideGate.setReferencePrice(s, 1.0850);
        }
        for (int i = 0; i < randomSymbols.length; i++) {
            randomSymbols[i] = rnd.nextInt(1_024);
            randomSides[i] = rnd.nextBoolean() ? Side.BUY : Side.SELL;
            randomPrices[i] = 1.0850 * (1 + (rnd.nextDouble() - 0.5) * 0.012);
        }
    }

    @Benchmark
    public int riskGateCheckRandomSymbols() {
        int i = cursor++ & (randomSymbols.length - 1);
        return wideGate.check(randomSymbols[i], randomSides[i], 1_000, randomPrices[i]);
    }

    @Benchmark
    public int riskGateCheck() {
        return gate.check(0, ((tick++ & 1) == 0) ? Side.BUY : Side.SELL, 1_000, 1.0850);
    }

    @Benchmark
    public int tickRingPublishDrain() {
        ring.publish(0, 1.0850, 1_000_000, tick++);
        return ring.drainTo(SINK, 1);
    }

    @Benchmark
    public double blackScholesPrice() {
        return BlackScholes.price(OptionType.CALL, 100, 100, 0.05, 0, 0.2, 1);
    }

    @Benchmark
    public double streamingEmaUpdate() {
        price += ((tick++ & 1) == 0) ? 1e-6 : -1e-6;
        return ema.update(price);
    }
}
