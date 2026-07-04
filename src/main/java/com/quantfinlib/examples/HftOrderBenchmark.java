package com.quantfinlib.examples;

import com.quantfinlib.indicators.StreamingIndicators;
import com.quantfinlib.marketdata.HftMarketDataBus;
import com.quantfinlib.orderbook.Side;
import com.quantfinlib.trading.HftOrderGateway;
import com.quantfinlib.trading.HftRiskGate;
import com.quantfinlib.util.LatencyRecorder;

import java.util.SplittableRandom;

/**
 * Benchmarks the fast-lane order path, mirroring {@link HftLatencyBenchmark}:
 *
 * <ol>
 *   <li><b>Risk gate</b>: ns per pre-trade check (tight loop).</li>
 *   <li><b>Submit-to-venue</b>: paced one-in-flight latency from
 *       {@code HftOrderGateway.submit()} to the venue thread receiving the
 *       order.</li>
 *   <li><b>Tick-to-order end-to-end</b>: market tick published on the
 *       {@code HftMarketDataBus} → strategy (2×EMA) on the bus consumer
 *       thread → risk check → order ring → venue thread. The full loop a
 *       live system runs, measured with the original tick timestamp.</li>
 *   <li><b>Throughput</b>: sustained orders/second under backpressure.</li>
 * </ol>
 *
 * <p>Run: {@code java -Xms512m -Xmx512m -XX:+AlwaysPreTouch -cp target/classes
 * com.quantfinlib.examples.HftOrderBenchmark}</p>
 */
public final class HftOrderBenchmark {

    private static final int WARMUP = 2_000_000;
    private static final int LATENCY_ORDERS = 1_000_000;
    private static final int E2E_TICKS = 500_000;
    private static final int THROUGHPUT_ORDERS = 20_000_000;

    public static void main(String[] args) throws Exception {
        riskGateMicrobench();
        submitToVenueLatency();
        tickToOrderEndToEnd();
        throughput();
    }

    private static HftRiskGate newGate() {
        HftRiskGate gate = new HftRiskGate(16)
                .maxOrderQuantity(1_000_000)
                .maxOrderNotional(1e12)
                .maxPositionQuantity(Long.MAX_VALUE / 4)
                .priceCollarPct(0.10);
        gate.setReferencePrice(0, 1.0850);
        return gate;
    }

    private static void riskGateMicrobench() {
        HftRiskGate gate = newGate();
        long blackhole = 0;
        for (int i = 0; i < WARMUP; i++) {
            blackhole += gate.check(0, Side.BUY, 1_000, 1.0850);
        }
        int n = 50_000_000;
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            blackhole += gate.check(0, (i & 1) == 0 ? Side.BUY : Side.SELL, 1_000, 1.0850);
        }
        long elapsed = System.nanoTime() - t0;
        System.out.printf("Risk gate: %.1f ns/check (%d checks, blackhole %d)%n",
                (double) elapsed / n, n, blackhole);
    }

    private static void submitToVenueLatency() throws Exception {
        try (HftOrderGateway gateway = new HftOrderGateway(1 << 13, newGate(), true)) {
            LatencyRecorder recorder = new LatencyRecorder();
            gateway.addOrderListener((id, sym, side, qty, px, ts) ->
                    recorder.record(System.nanoTime() - ts));
            gateway.start();

            pacedSubmit(gateway, WARMUP / 4);
            recorder.reset();
            pacedSubmit(gateway, LATENCY_ORDERS);
            System.out.println("Submit-to-venue latency: " + recorder.summary());
        }
    }

    private static void pacedSubmit(HftOrderGateway gateway, int orders) {
        long base = gateway.deliveredCount();
        for (int i = 0; i < orders; i++) {
            while (gateway.submit(0, Side.BUY, 1_000, 1.0850, System.nanoTime()) <= 0) {
                Thread.onSpinWait();
            }
            long need = base + i + 1;
            while (gateway.deliveredCount() < need) {
                Thread.onSpinWait();
            }
        }
    }

    /** The full live loop: tick -> strategy -> risk gate -> order -> venue. */
    private static void tickToOrderEndToEnd() throws Exception {
        HftRiskGate gate = newGate();
        try (HftMarketDataBus bus = new HftMarketDataBus(1 << 14, 16, true);
             HftOrderGateway gateway = new HftOrderGateway(1 << 13, gate, true)) {

            int eurusd = bus.registerSymbol("EURUSD");
            LatencyRecorder recorder = new LatencyRecorder();
            gateway.addOrderListener((id, sym, side, qty, px, ts) ->
                    recorder.record(System.nanoTime() - ts));   // ts = original tick time

            StreamingIndicators.Ema fast = new StreamingIndicators.Ema(12);
            StreamingIndicators.Ema slow = new StreamingIndicators.Ema(26);
            bus.subscribe(eurusd, (id, price, size, tickTs) -> {
                gate.setReferencePrice(eurusd, price);
                double f = fast.update(price);
                double s = slow.update(price);
                // Submit on every decided tick so the path is exercised per tick.
                Side side = f > s ? Side.BUY : Side.SELL;
                if (!Double.isNaN(s)) {
                    gateway.submit(eurusd, side, 1_000, price, tickTs);
                }
            });
            gateway.start();
            bus.start();

            SplittableRandom rnd = new SplittableRandom(42);
            double price = 1.0850;
            long deliveredBase = gateway.deliveredCount();
            int submittedTarget = 0;
            for (int i = 0; i < WARMUP / 4 + E2E_TICKS; i++) {
                if (i == WARMUP / 4) {
                    recorder.reset();
                }
                price *= 1 + (rnd.nextDouble() - 0.5) * 2e-5;
                long tickTs = System.nanoTime();
                while (!bus.publish(eurusd, price, 1_000_000, tickTs)) {
                    Thread.onSpinWait();
                }
                if (i >= 25) {                        // EMAs warmed: an order per tick
                    submittedTarget++;
                }
                long need = deliveredBase + submittedTarget;
                while (gateway.deliveredCount() < need) {
                    Thread.onSpinWait();
                }
            }
            System.out.println("Tick-to-order end-to-end:    " + recorder.summary());
        }
    }

    private static void throughput() throws Exception {
        try (HftOrderGateway gateway = new HftOrderGateway(1 << 14, newGate(), true)) {
            long[] blackhole = new long[1];
            gateway.addOrderListener((id, sym, side, qty, px, ts) -> blackhole[0] += qty);
            gateway.start();

            long t0 = System.nanoTime();
            for (int i = 0; i < THROUGHPUT_ORDERS; i++) {
                while (gateway.submit(0, (i & 1) == 0 ? Side.BUY : Side.SELL,
                        1_000, 1.0850, 0) <= 0) {
                    Thread.onSpinWait();
                }
            }
            while (gateway.deliveredCount() < THROUGHPUT_ORDERS) {
                Thread.onSpinWait();
            }
            long elapsed = System.nanoTime() - t0;
            System.out.printf("Throughput: %.1f million orders/sec (%d orders in %.2f s, "
                            + "%d backpressure retries, blackhole %d)%n",
                    THROUGHPUT_ORDERS / (elapsed / 1e9) / 1e6, THROUGHPUT_ORDERS,
                    elapsed / 1e9, gateway.ringFullCount(), blackhole[0]);
        }
    }
}
