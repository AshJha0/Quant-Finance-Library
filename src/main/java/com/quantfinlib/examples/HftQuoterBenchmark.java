package com.quantfinlib.examples;

import com.quantfinlib.marketdata.HftMarketDataBus;
import com.quantfinlib.microstructure.TickSizeSchedule;
import com.quantfinlib.trading.HftOrderGateway;
import com.quantfinlib.trading.HftQuoter;
import com.quantfinlib.trading.HftRiskGate;
import com.quantfinlib.util.HiccupMonitor;
import com.quantfinlib.util.LatencyRecorder;

import java.util.SplittableRandom;

/**
 * Benchmarks the market-making loop end-to-end, completing the fast-lane
 * benchmark family ({@link HftLatencyBenchmark}: publish→strategy,
 * {@link HftOrderBenchmark}: tick→order):
 *
 * <pre>
 *   tick on the bus → HftQuoter (mid + inventory skew + tick-grid snap)
 *        → risk gate ×2 → order ring → venue thread receives BOTH sides
 * </pre>
 *
 * <p>Latency is stamped from the original tick timestamp to the venue
 * thread receiving the <b>second</b> side of the quote — the full
 * tick-to-two-sided-quote path a live maker runs, including the grid
 * rounding and the two risk-gate passes.</p>
 *
 * <p>Run: {@code java -Xms512m -Xmx512m -XX:+AlwaysPreTouch -cp target/classes
 * com.quantfinlib.examples.HftQuoterBenchmark}</p>
 */
public final class HftQuoterBenchmark {

    private static final int WARMUP_TICKS = 500_000;
    private static final int MEASURED_TICKS = 500_000;

    public static void main(String[] args) throws Exception {
        // Hiccups printed alongside: if a latency max coincides with a
        // platform stall here, the OS owns that tail, not the quoter.
        try (HiccupMonitor hiccups = new HiccupMonitor().start()) {
            tickToQuoteEndToEnd();
            System.out.println(hiccups.summary());
        }
    }

    private static void tickToQuoteEndToEnd() throws Exception {
        HftRiskGate gate = new HftRiskGate(16)
                .maxOrderQuantity(1_000_000)
                .maxOrderNotional(1e12)
                .maxPositionQuantity(Long.MAX_VALUE / 4)
                .priceCollarPct(0.10);
        gate.setReferencePrice(0, 1.0850);

        try (HftMarketDataBus bus = new HftMarketDataBus(1 << 14, 16, true);
             HftOrderGateway gateway = new HftOrderGateway(1 << 13, gate, true)) {

            int eurusd = bus.registerSymbol("EURUSD");
            LatencyRecorder recorder = new LatencyRecorder();

            // The venue thread sees two orders per quote update; record the
            // completed two-sided quote on the second (SELL) side.
            gateway.addOrderListener((id, sym, side, qty, px, ts) -> {
                if (side == com.quantfinlib.orderbook.Side.SELL) {
                    recorder.record(System.nanoTime() - ts); // ts = tick time
                }
            });

            // Realistic quoting config: half-spread + inventory skew +
            // FX-style grid snapping, conflation off so every tick quotes
            // (the benchmark measures the full path, not the suppressor).
            HftQuoter quoter = new HftQuoter(gateway, 16, HftQuoter.Config
                    .of(100_000, 0.00005)
                    .withSkewPerUnit(1e-10)
                    .withTickSchedule(TickSizeSchedule.flat(0.00001)));
            bus.subscribe(eurusd, quoter);

            gateway.start();
            bus.start();

            SplittableRandom rnd = new SplittableRandom(42);
            double price = 1.0850;
            long quoted = 0;
            for (int i = 0; i < WARMUP_TICKS + MEASURED_TICKS; i++) {
                if (i == WARMUP_TICKS) {
                    recorder.reset();
                }
                price *= 1 + (rnd.nextDouble() - 0.5) * 2e-5;
                long tickTs = System.nanoTime();
                while (!bus.publish(eurusd, price, 1_000_000, tickTs)) {
                    Thread.onSpinWait();
                }
                // Pace one-in-flight: wait for both sides at the venue so
                // queueing never pollutes the per-quote latency numbers.
                quoted += 2;
                while (gateway.deliveredCount() < quoted) {
                    Thread.onSpinWait();
                }
            }
            System.out.println("Tick-to-two-sided-quote:     " + recorder.summary());
            System.out.printf("Quote updates: %d, suppressed: %d, rejected sides: %d%n",
                    quoter.quoteUpdates(), quoter.suppressedUpdates(), quoter.rejectedSides());
        }
    }
}
