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
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class CoreBenchmarks {

    private static final TickListener SINK = (id, price, size, ts) -> {
    };

    private HftRiskGate gate;
    private TickRingBuffer ring;
    private StreamingIndicators.Ema ema;
    private long tick;
    private double price;

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
