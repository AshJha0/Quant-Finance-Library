package com.quantfinlib.examples;

import com.quantfinlib.fx.CrossRateEngine;
import com.quantfinlib.marketdata.HftMarketDataBus;
import com.quantfinlib.trading.HftOrderGateway;
import com.quantfinlib.trading.HftQuoter;
import com.quantfinlib.trading.HftRiskGate;
import com.quantfinlib.util.HiccupMonitor;

import java.util.SplittableRandom;

/**
 * Scale probe, parameterized: args = [crossCount, measuredTicks, crossMinMove].
 * 200 direct pairs, crossCount synthetic crosses with legs spread across the
 * direct pairs (~crossCount/100 crosses touched per direct tick). Direct
 * pairs quote unconditionally; crosses quote through per-symbol conflation
 * with the given min-move (0 = conflation off, quote every cross update).
 */
public final class ScaleBenchmark {

    private static final int DIRECT = 200;

    public static void main(String[] args) throws Exception {
        int crosses = args.length > 0 ? Integer.parseInt(args[0]) : 100;
        int measured = args.length > 1 ? Integer.parseInt(args[1]) : 5_000_000;
        double crossMinMove = args.length > 2 ? Double.parseDouble(args[2]) : 0;
        int warmup = Math.max(100_000, measured / 5);
        int maxSymbols = Integer.highestOneBit(DIRECT + crosses) * 2;

        HftRiskGate gate = new HftRiskGate(maxSymbols)
                .maxOrderQuantity(10_000_000)
                .maxOrderNotional(1e15)
                .maxPositionQuantity(Long.MAX_VALUE / 4);

        try (HiccupMonitor hiccups = new HiccupMonitor().start();
             HftMarketDataBus bus = new HftMarketDataBus(1 << 16, maxSymbols, true);
             HftOrderGateway gateway = new HftOrderGateway(1 << 16, gate, true)) {

            long[] delivered = {0};
            gateway.addOrderListener((id, sym, side, qty, px, ts) -> delivered[0]++);

            HftQuoter quoter = new HftQuoter(gateway, maxSymbols,
                    HftQuoter.Config.of(100_000, 0.0002).withSkewPerUnit(1e-10));

            int[] ids = new int[DIRECT];
            for (int i = 0; i < DIRECT; i++) {
                ids[i] = bus.registerSymbol("P" + i);
                bus.subscribe(ids[i], quoter);
            }
            // Crosses: legs spread deterministically over the direct pairs.
            HftQuoter.Config crossCfg = HftQuoter.Config.of(100_000, 0.0002)
                    .withSkewPerUnit(1e-10)
                    // Move-gated: interval effectively infinite so suppression
                    // is governed purely by minMove (BOTH gates must pass).
                    .withConflation(crossMinMove == 0 ? 0 : Long.MAX_VALUE / 4, crossMinMove);
            CrossRateEngine engine = new CrossRateEngine(bus);
            for (int c = 0; c < crosses; c++) {
                int legA = c % DIRECT;
                int legB = (c * 7 + 13) % DIRECT;
                if (legB == legA) {
                    legB = (legB + 1) % DIRECT;
                }
                int crossId = engine.addCross("P" + legA, "P" + legB, "X" + c,
                        CrossRateEngine.Op.MULTIPLY,
                        (symbolId, price, size, ts) -> quoter.onTick(symbolId, price, size, ts));
                quoter.configureSymbol(crossId, crossCfg);
            }

            gateway.start();
            bus.start();

            SplittableRandom rnd = new SplittableRandom(42);
            double[] px = new double[DIRECT];
            java.util.Arrays.fill(px, 1.0850);

            long t0 = 0;
            for (int i = 0; i < warmup + measured; i++) {
                if (i == warmup) {
                    t0 = System.nanoTime();
                }
                int s = i % DIRECT;
                px[s] *= 1 + (rnd.nextDouble() - 0.5) * 2e-5;
                while (!bus.publish(ids[s], px[s], 1_000_000, System.nanoTime())) {
                    Thread.onSpinWait();
                }
            }
            long deadline = System.nanoTime() + 30_000_000_000L;
            while (bus.processedCount() < warmup + measured && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            long elapsed = System.nanoTime() - t0;

            System.out.printf("crosses=%d, crossMinMove=%s:%n", crosses,
                    crossMinMove == 0 ? "OFF (quote every update)" : crossMinMove);
            System.out.printf("  Inbound: %.2f million ticks/sec sustained "
                            + "(~%.1f us consumer budget per direct tick)%n",
                    measured / (elapsed / 1e9) / 1e6, elapsed / 1e3 / measured);
            System.out.printf("  Orders:  %.2f million/sec delivered; quoteUpdates=%d, "
                            + "suppressed=%d, rejectedSides=%d%n",
                    delivered[0] / (elapsed / 1e9) / 1e6,
                    quoter.quoteUpdates(), quoter.suppressedUpdates(), quoter.rejectedSides());
            System.out.println("  " + hiccups.summary());
        }
    }
}
