package com.quantfinlib.examples;

import com.quantfinlib.marketdata.HftMarketDataBus;
import com.quantfinlib.microstructure.TickSizeSchedule;
import com.quantfinlib.orderbook.Side;
import com.quantfinlib.trading.HftOrderGateway;
import com.quantfinlib.trading.HftQuoter;
import com.quantfinlib.trading.HftRiskGate;
import com.quantfinlib.trading.LoopbackVenue;
import com.quantfinlib.trading.OrderListener;
import com.quantfinlib.util.HiccupMonitor;
import com.quantfinlib.util.LatencyRecorder;

import java.util.SplittableRandom;

/**
 * Benchmarks the market-making loop end to end:
 *
 * <pre>
 *   tick on the bus → HftQuoter (fair value, skew, grid snap)
 *        → cancel/replace of the working bid and offer through the risk gate
 *        → order ring → venue thread receives the offer-side message
 * </pre>
 *
 * <p>A {@link LoopbackVenue} acknowledges new orders and confirms replaces,
 * so the quoter keeps one working order per side. Latency is from the tick
 * timestamp to the venue thread receiving the offer-side message (new order
 * or replace) of that update. Two runs: closed loop (one tick in flight,
 * understates tails) and open loop at a fixed rate with intended-start
 * timestamps (coordinated-omission corrected). Ticks that produce no
 * offer-side message (the price snapped to the same grid point, or the side
 * was still waiting for the venue) record nothing.</p>
 *
 * <p>Run: {@code java -Xms512m -Xmx512m -XX:+AlwaysPreTouch -cp target/classes
 * com.quantfinlib.examples.HftQuoterBenchmark [ratePerSec]}</p>
 */
public final class HftQuoterBenchmark {

    private static final int WARMUP_TICKS = 500_000;
    private static final int MEASURED_TICKS = 500_000;

    public static void main(String[] args) throws Exception {
        long ratePerSec = args.length > 0 ? Long.parseLong(args[0]) : 100_000;
        try (HiccupMonitor hiccups = new HiccupMonitor().start()) {
            tickToQuote(ratePerSec);
            System.out.println(hiccups.summary());
        }
    }

    private static void tickToQuote(long ratePerSec) throws Exception {
        HftRiskGate gate = new HftRiskGate(16)
                .maxOrderQuantity(1_000_000)
                .maxOrderNotional(1e12)
                .maxPositionQuantity(10_000_000)
                .priceCollarPct(0.10);
        gate.setReferencePrice(0, 1.0850);

        try (HftMarketDataBus bus = new HftMarketDataBus(1 << 14, 16, true);
             HftOrderGateway gateway = new HftOrderGateway(1 << 13, gate, true)) {

            int eurusd = bus.registerSymbol("EURUSD");
            LatencyRecorder recorder = new LatencyRecorder();
            gateway.addOrderListener(new OrderListener() {
                @Override
                public void onOrder(long id, int sym, Side side, long qty, double px, long ts) {
                    if (side == Side.SELL) {
                        recorder.record(System.nanoTime() - ts);
                    }
                }

                @Override
                public void onReplace(long id, int sym, Side side, long qty, double px, long ts) {
                    if (side == Side.SELL) {
                        recorder.record(System.nanoTime() - ts);
                    }
                }
            });
            gateway.addOrderListener(new LoopbackVenue(gateway));

            HftQuoter quoter = new HftQuoter(gateway, 16, HftQuoter.Config
                    .of(100_000, 0.00005)
                    .withSkewPerUnit(1e-10)
                    .withTickSchedule(TickSizeSchedule.flat(0.00001)));
            bus.subscribe(eurusd, quoter);
            gateway.start();
            bus.start();

            SplittableRandom rnd = new SplittableRandom(42);
            double price = 1.0850;
            long published = 0;
            for (int i = 0; i < WARMUP_TICKS + MEASURED_TICKS; i++) {
                if (i == WARMUP_TICKS) {
                    recorder.reset();
                }
                price *= 1 + (rnd.nextDouble() - 0.5) * 2e-5;
                while (!bus.publish(eurusd, price, 1_000_000, System.nanoTime())) {
                    Thread.onSpinWait();
                }
                published++;
                awaitQuiet(bus, gateway, published);
            }
            System.out.println("Closed-loop tick-to-quote: " + recorder.summary());

            recorder.reset();
            long period = 1_000_000_000L / ratePerSec;
            long start = System.nanoTime() + 1_000_000;
            for (int i = 0; i < MEASURED_TICKS; i++) {
                long intended = start + i * period;
                while (System.nanoTime() < intended) {
                    Thread.onSpinWait();
                }
                price *= 1 + (rnd.nextDouble() - 0.5) * 2e-5;
                while (!bus.publish(eurusd, price, 1_000_000, intended)) {
                    Thread.onSpinWait();
                }
                published++;
            }
            awaitQuiet(bus, gateway, published);
            System.out.printf("Open-loop at %,d ticks/s (intended-start): %s%n",
                    ratePerSec, recorder.summary());
            System.out.printf("Quote updates: %d, replaces: %d, deferred sides: %d, "
                            + "suppressed: %d, rejected sides: %d%n",
                    quoter.quoteUpdates(), quoter.replaces(), quoter.deferredSides(),
                    quoter.suppressedUpdates(), quoter.rejectedSides());
        }
    }

    /** Waits until the bus consumed every tick and the venue thread every order message. */
    private static void awaitQuiet(HftMarketDataBus bus, HftOrderGateway gateway, long ticks) {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (bus.processedCount() >= ticks) {
                long sent = gateway.submittedCount() + gateway.replaceRequestCount()
                        + gateway.cancelRequestCount();
                if (gateway.deliveredCount() >= sent) {
                    return;
                }
            }
            Thread.onSpinWait();
        }
    }

    private HftQuoterBenchmark() {
    }
}
