package com.quantfinlib.examples;

import com.quantfinlib.indicators.StreamingIndicators;
import com.quantfinlib.marketdata.HftMarketDataBus;
import com.quantfinlib.core.Side;
import com.quantfinlib.trading.HftOrderGateway;
import com.quantfinlib.trading.HftRiskGate;
import com.quantfinlib.trading.LoopbackVenue;
import com.quantfinlib.util.HiccupMonitor;
import com.quantfinlib.util.LatencyRecorder;

import java.util.SplittableRandom;

/**
 * Benchmarks the fast-lane order path:
 *
 * <ol>
 *   <li><b>Risk gate cost</b>: checks over 1,024 symbols picked at random
 *       (positions, reservations and references spread over many cache
 *       lines), random sides and prices. This is reciprocal throughput of
 *       independent checks, not the latency of one check in a pipeline; the
 *       JMH {@code CoreBenchmarks.riskGateCheckRandomSymbols} is the
 *       statistically controlled figure.</li>
 *   <li><b>Submit-to-venue</b>, closed loop and open loop (fixed rate,
 *       intended-start timestamps, coordinated-omission corrected).</li>
 *   <li><b>Tick-to-order end-to-end</b>: the feed thread updates the collar
 *       reference and publishes the tick; the strategy on the bus consumer
 *       prices its order from its own EMA, so the collar compares two
 *       independent prices and can fire; position limits are finite.</li>
 *   <li><b>Throughput</b>: sustained orders per second under backpressure.</li>
 * </ol>
 *
 * <p>An in-process {@link LoopbackVenue} fills every order on arrival so
 * the order-state table recycles its slots.</p>
 *
 * <p>Run: {@code java -Xms512m -Xmx512m -XX:+AlwaysPreTouch -cp target/classes
 * com.quantfinlib.examples.HftOrderBenchmark [ratePerSec]}</p>
 */
public final class HftOrderBenchmark {

    private static final int WARMUP = 2_000_000;
    private static final int LATENCY_ORDERS = 1_000_000;
    private static final int E2E_TICKS = 500_000;
    private static final int THROUGHPUT_ORDERS = 20_000_000;
    private static final int SYMBOLS = 1_024;

    public static void main(String[] args) throws Exception {
        long ratePerSec = args.length > 0 ? Long.parseLong(args[0]) : 200_000;
        try (HiccupMonitor hiccups = new HiccupMonitor().start()) {
            riskGateCost();
            submitToVenueLatency(ratePerSec);
            tickToOrderEndToEnd();
            throughput();
            System.out.println(hiccups.summary());
        }
    }

    private static HftRiskGate newGate(int symbols) {
        HftRiskGate gate = new HftRiskGate(symbols)
                .maxOrderQuantity(1_000_000)
                .maxOrderNotional(1e9)
                .maxPositionQuantity(10_000_000)
                .priceCollarPct(0.005);
        for (int s = 0; s < symbols; s++) {
            gate.setReferencePrice(s, 1.0850);
        }
        return gate;
    }

    private static void riskGateCost() {
        HftRiskGate gate = newGate(SYMBOLS);
        SplittableRandom rnd = new SplittableRandom(7);
        int n = 1 << 20;
        int[] syms = new int[n];
        Side[] sides = new Side[n];
        double[] prices = new double[n];
        for (int i = 0; i < n; i++) {
            syms[i] = rnd.nextInt(SYMBOLS);
            sides[i] = rnd.nextBoolean() ? Side.BUY : Side.SELL;
            prices[i] = 1.0850 * (1 + (rnd.nextDouble() - 0.5) * 0.012); // ~some collar hits
        }
        long blackhole = 0;
        for (int r = 0; r < 4; r++) {
            for (int i = 0; i < n; i++) {
                blackhole += gate.check(syms[i], sides[i], 1_000, prices[i]);
            }
        }
        int rounds = 32;
        long t0 = System.nanoTime();
        for (int r = 0; r < rounds; r++) {
            for (int i = 0; i < n; i++) {
                blackhole += gate.check(syms[i], sides[i], 1_000, prices[i]);
            }
        }
        long elapsed = System.nanoTime() - t0;
        System.out.printf("Risk gate: %.1f ns per check, reciprocal throughput over %d random "
                        + "symbols (%d checks, %d collar rejects, blackhole %d)%n",
                (double) elapsed / ((long) rounds * n), SYMBOLS, (long) rounds * n,
                gate.rejectionCount(HftRiskGate.REJECT_PRICE_COLLAR), blackhole);
    }

    private static void submitToVenueLatency(long ratePerSec) throws Exception {
        HftRiskGate gate = newGate(16);
        try (HftOrderGateway gateway = new HftOrderGateway(1 << 13, gate, true)) {
            LatencyRecorder recorder = new LatencyRecorder();
            gateway.addOrderListener((id, sym, side, qty, px, ts) ->
                    recorder.record(System.nanoTime() - ts));
            gateway.addOrderListener(new LoopbackVenue(gateway, LoopbackVenue.FILL_ALL, 1 << 13));
            gateway.start();

            closedLoop(gateway, WARMUP / 4);
            recorder.reset();
            closedLoop(gateway, LATENCY_ORDERS);
            System.out.println("Closed-loop submit-to-venue: " + recorder.summary());

            recorder.reset();
            long period = 1_000_000_000L / ratePerSec;
            long start = System.nanoTime() + 1_000_000;
            long base = gateway.deliveredCount();
            for (int i = 0; i < LATENCY_ORDERS; i++) {
                long intended = start + i * period;
                while (System.nanoTime() < intended) {
                    Thread.onSpinWait();
                }
                while (gateway.submit(0, (i & 1) == 0 ? Side.BUY : Side.SELL, 1_000, 1.0850,
                        intended) == 0) {
                    Thread.onSpinWait();     // ring full: the wait is charged to this order
                }
            }
            awaitDelivered(gateway, base + LATENCY_ORDERS);
            System.out.printf("Open-loop at %,d orders/s (intended-start): %s%n",
                    ratePerSec, recorder.summary());
        }
    }

    private static void closedLoop(HftOrderGateway gateway, int orders) {
        long base = gateway.deliveredCount();
        for (int i = 0; i < orders; i++) {
            while (gateway.submit(0, (i & 1) == 0 ? Side.BUY : Side.SELL, 1_000, 1.0850,
                    System.nanoTime()) == 0) {
                Thread.onSpinWait();
            }
            awaitDelivered(gateway, base + i + 1);
        }
    }

    /** The full loop: feed -> tick -> strategy -> risk gate -> order -> venue. */
    private static void tickToOrderEndToEnd() throws Exception {
        HftRiskGate gate = newGate(16);
        try (HftMarketDataBus bus = new HftMarketDataBus(1 << 14, 16, true);
             HftOrderGateway gateway = new HftOrderGateway(1 << 13, gate, true)) {

            int eurusd = bus.registerSymbol("EURUSD");
            LatencyRecorder recorder = new LatencyRecorder();
            gateway.addOrderListener((id, sym, side, qty, px, ts) ->
                    recorder.record(System.nanoTime() - ts));   // ts = original tick time
            gateway.addOrderListener(new LoopbackVenue(gateway, LoopbackVenue.FILL_ALL, 1 << 13));

            StreamingIndicators.Ema fast = new StreamingIndicators.Ema(12);
            StreamingIndicators.Ema slow = new StreamingIndicators.Ema(26);
            long[] rejected = new long[1];
            bus.subscribe(eurusd, (id, price, size, tickTs) -> {
                double f = fast.update(price);
                double s = slow.update(price);
                if (!Double.isNaN(s)) {
                    // The strategy prices off its own fast EMA, not the tick.
                    Side side = f > s ? Side.BUY : Side.SELL;
                    if (gateway.submit(eurusd, side, 1_000, f, tickTs) <= 0) {
                        rejected[0]++;
                    }
                }
            });
            gateway.start();
            bus.start();

            SplittableRandom rnd = new SplittableRandom(42);
            double price = 1.0850;
            for (int i = 0; i < WARMUP / 4 + E2E_TICKS; i++) {
                if (i == WARMUP / 4) {
                    recorder.reset();
                }
                price *= 1 + (rnd.nextDouble() - 0.5) * 2e-5;
                // Feed thread: independent collar reference, then the tick.
                gate.setReferencePrice(eurusd, price);
                long processed = bus.processedCount();
                long tickTs = System.nanoTime();
                while (!bus.publish(eurusd, price, 1_000_000, tickTs)) {
                    Thread.onSpinWait();
                }
                while (bus.processedCount() <= processed) {
                    Thread.onSpinWait();
                }
                // Closed loop: wait until any order this tick produced has
                // reached the venue (the strategy may also have been refused).
                awaitDelivered(gateway, gateway.submittedCount());
            }
            System.out.println("Tick-to-order end-to-end (closed loop): " + recorder.summary());
            System.out.printf("Orders refused by the gate: %d (collar %d, position %d)%n",
                    rejected[0], gate.rejectionCount(HftRiskGate.REJECT_PRICE_COLLAR),
                    gate.rejectionCount(HftRiskGate.REJECT_POSITION));
        }
    }

    private static void throughput() throws Exception {
        try (HftOrderGateway gateway = new HftOrderGateway(1 << 14, newGate(16), true)) {
            gateway.addOrderListener(new LoopbackVenue(gateway, LoopbackVenue.FILL_ALL, 1 << 14));
            gateway.start();
            long base = gateway.deliveredCount();
            long t0 = System.nanoTime();
            for (int i = 0; i < THROUGHPUT_ORDERS; i++) {
                while (gateway.submit(0, (i & 1) == 0 ? Side.BUY : Side.SELL,
                        1_000, 1.0850, 0) <= 0) {
                    Thread.onSpinWait();
                }
            }
            awaitDelivered(gateway, base + THROUGHPUT_ORDERS);
            long elapsed = System.nanoTime() - t0;
            System.out.printf("Throughput: %.1f million orders/sec (%d orders in %.2f s, "
                            + "%d backpressure retries)%n",
                    THROUGHPUT_ORDERS / (elapsed / 1e9) / 1e6, THROUGHPUT_ORDERS,
                    elapsed / 1e9, gateway.ringFullCount());
        }
    }

    private static void awaitDelivered(HftOrderGateway gateway, long target) {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (gateway.deliveredCount() < target && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    private HftOrderBenchmark() {
    }
}
