package com.quantfinlib.examples;

import com.quantfinlib.trading.HftQuoter;
import com.quantfinlib.trading.HftRiskGate;
import com.quantfinlib.trading.ShardedTradingEngine;

import java.util.SplittableRandom;

/**
 * Horizontal-scaling probe: 300 direct symbols spread across k shards, all
 * quoted two-sided on every tick (conflation off = worst case), single
 * producer round-robin. args[0] = shard count.
 */
public final class ShardScaleBenchmark {

    private static final int SYMBOLS = 300;
    private static final int WARMUP = 1_000_000;
    private static final int MEASURED = 5_000_000;

    public static void main(String[] args) throws Exception {
        int shards = args.length > 0 ? Integer.parseInt(args[0]) : 1;
        System.out.printf("shards=%d (cores available: %d)%n",
                shards, Runtime.getRuntime().availableProcessors());

        try (ShardedTradingEngine engine = new ShardedTradingEngine(
                shards, 1 << 15, 1 << 15, 512, true,
                s -> new HftRiskGate(512)
                        .maxOrderQuantity(10_000_000)
                        .maxOrderNotional(1e15)
                        .maxPositionQuantity(Long.MAX_VALUE / 4))) {

            // Per-shard counters, 8 slots apart (one cache line) — each venue
            // thread is the SINGLE writer of its own slot. The first version
            // of this probe used one synchronized counter across shards and
            // measured sharding as a SLOWDOWN: the instrumentation itself
            // was the shared state sharding exists to eliminate.
            long[] delivered = new long[shards * 8];
            HftQuoter[] quoters = new HftQuoter[shards];
            int[] handles = new int[SYMBOLS];
            for (int i = 0; i < SYMBOLS; i++) {
                handles[i] = engine.registerSymbol("P" + i, i % shards);
            }
            for (int s = 0; s < shards; s++) {
                quoters[s] = new HftQuoter(engine.gateway(s), 512,
                        HftQuoter.Config.of(100_000, 0.0002).withSkewPerUnit(1e-10));
                engine.bus(s).subscribeAll(quoters[s]);
                final int slot = s * 8;
                engine.gateway(s).addOrderListener((id, sym, side, qty, px, ts) ->
                        delivered[slot]++);
            }
            engine.start();

            SplittableRandom rnd = new SplittableRandom(42);
            double[] px = new double[SYMBOLS];
            java.util.Arrays.fill(px, 1.0850);

            long t0 = 0;
            for (int i = 0; i < WARMUP + MEASURED; i++) {
                if (i == WARMUP) {
                    t0 = System.nanoTime();
                }
                int s = i % SYMBOLS;
                px[s] *= 1 + (rnd.nextDouble() - 0.5) * 2e-5;
                while (!engine.publish(handles[s], px[s], 1_000_000, System.nanoTime())) {
                    Thread.onSpinWait();
                }
            }
            long deadline = System.nanoTime() + 30_000_000_000L;
            while (engine.processedCount() < WARMUP + MEASURED && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            long elapsed = System.nanoTime() - t0;
            long rejects = 0;
            for (HftQuoter q : quoters) {
                rejects += q.rejectedSides();
            }
            System.out.printf("  Inbound: %.2f million ticks/sec aggregate "
                            + "(%.2fM per shard)%n",
                    MEASURED / (elapsed / 1e9) / 1e6,
                    MEASURED / (elapsed / 1e9) / 1e6 / shards);
            System.out.printf("  Orders delivered: %.1fM (%.1fM/sec), "
                            + "rejected/dropped sides: %d%n",
                    engine.deliveredCount() / 1e6,
                    engine.deliveredCount() / (elapsed / 1e9) / 1e6, rejects);
        }
    }
}
