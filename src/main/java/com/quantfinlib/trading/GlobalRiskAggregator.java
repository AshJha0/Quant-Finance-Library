package com.quantfinlib.trading;

import java.util.List;
import java.util.concurrent.locks.LockSupport;

/**
 * Firm-wide risk across shards — the piece sharding deliberately doesn't
 * solve: each shard's {@link HftRiskGate} sees only its own symbols, so a
 * "total gross notional across the firm" cap needs someone who can see all
 * of them. This aggregator is that someone, built so the hot paths never
 * pay for it:
 *
 * <ul>
 *   <li>a monitor thread polls every gate's positions and reference prices
 *       (both already published with release/acquire semantics — the
 *       VarHandle work done for the gate's own correctness is exactly what
 *       makes cross-thread aggregation free);</li>
 *   <li>gross notional = Σ |position| × referencePrice over all gates and
 *       symbols (symbols without a reference price contribute zero — set
 *       references from the market data thread, as the quoting path
 *       already does);</li>
 *   <li>breach → {@link HftRiskGate#kill} on EVERY gate: one released
 *       boolean, read by each shard's checks as a single acquire load.
 *       Recovery is hysteretic: trading resumes only below
 *       {@code cap × resumeFraction}, so the firm doesn't flap around the
 *       limit.</li>
 * </ul>
 *
 * <p>The detection latency is the poll interval (default 1 ms) — a
 * deliberate trade: pre-trade per-order checks stay per-shard and
 * nanosecond-cheap, while the firm-wide cap is a circuit breaker, not a
 * per-order gate. That is how real risk stacks layer it.</p>
 */
public final class GlobalRiskAggregator implements AutoCloseable {

    private final List<HftRiskGate> gates;
    private final double maxGrossNotional;
    private final double resumeNotional;
    private final long pollIntervalNanos;
    private final Thread monitor;
    private volatile boolean running = true;
    private volatile boolean tripped;
    private volatile double lastGross;
    private volatile long trips;

    /**
     * @param gates            every shard's gate (see {@code ShardedTradingEngine.gates()})
     * @param maxGrossNotional firm-wide cap on Σ |position| × referencePrice
     * @param resumeFraction   resume trading below cap × this (e.g. 0.9)
     * @param pollIntervalNanos monitor cadence (1_000_000 = 1 ms detection)
     */
    public GlobalRiskAggregator(List<HftRiskGate> gates, double maxGrossNotional,
                                double resumeFraction, long pollIntervalNanos) {
        if (gates.isEmpty() || maxGrossNotional <= 0
                || resumeFraction <= 0 || resumeFraction >= 1 || pollIntervalNanos <= 0) {
            throw new IllegalArgumentException(
                    "need gates, cap > 0, 0 < resumeFraction < 1, poll > 0");
        }
        this.gates = List.copyOf(gates);
        this.maxGrossNotional = maxGrossNotional;
        this.resumeNotional = maxGrossNotional * resumeFraction;
        this.pollIntervalNanos = pollIntervalNanos;
        this.monitor = new Thread(this::monitorLoop, "global-risk-aggregator");
        this.monitor.setDaemon(true);
        this.monitor.start();
    }

    private boolean[] killedBeforeTrip;

    private void monitorLoop() {
        while (running) {
            double gross = grossNotional();
            lastGross = gross;
            if (!tripped && gross > maxGrossNotional) {
                // Snapshot which gates were ALREADY killed (ops holds, a
                // second breaker): recovery must not clear a kill this
                // aggregator did not set. A kill placed DURING our trip is
                // indistinguishable from ours (kill(true) is idempotent) —
                // stated limitation; pre-existing holds are preserved.
                killedBeforeTrip = new boolean[gates.size()];
                for (int i = 0; i < gates.size(); i++) {
                    killedBeforeTrip[i] = gates.get(i).isKilled();
                    gates.get(i).kill(true);
                }
                tripped = true;
                trips++;
            } else if (tripped && gross < resumeNotional) {
                for (int i = 0; i < gates.size(); i++) {
                    if (!killedBeforeTrip[i]) {
                        gates.get(i).kill(false);
                    }
                }
                tripped = false;
            }
            LockSupport.parkNanos(pollIntervalNanos);
        }
    }

    /** One sweep over every gate's positions — acquire reads only. */
    public double grossNotional() {
        double gross = 0;
        for (HftRiskGate gate : gates) {
            int n = gate.symbolCapacity();
            for (int s = 0; s < n; s++) {
                long position = gate.position(s);
                if (position != 0) {
                    double ref = gate.referencePrice(s);
                    if (ref == ref) { // NaN-safe: unpriced symbols contribute 0
                        gross += Math.abs(position) * ref;
                    }
                }
            }
        }
        return gross;
    }

    /** Whether the firm-wide breaker is currently tripped. */
    public boolean isTripped() {
        return tripped;
    }

    /** Gross notional from the most recent monitor sweep. */
    public double lastGrossNotional() {
        return lastGross;
    }

    /** Times the breaker has tripped over this aggregator's life. */
    public long tripCount() {
        return trips;
    }

    /** Stops the monitor; gates keep whatever kill state they last had. */
    @Override
    public void close() {
        running = false;
        LockSupport.unpark(monitor);
        try {
            monitor.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
