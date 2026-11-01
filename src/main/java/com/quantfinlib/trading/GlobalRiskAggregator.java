package com.quantfinlib.trading;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Firm-wide risk across shards. Each shard's {@link HftRiskGate} sees only
 * its own symbols, so a cap on total gross notional needs a monitor that
 * reads all of them. The hot paths never pay for it:
 *
 * <ul>
 *   <li>a monitor thread polls every gate's positions, open-order
 *       reservations and reference prices (all published with
 *       release/acquire semantics);</li>
 *   <li>worst-case gross notional per symbol is
 *       {@code max(|position + openBuy|, |position - openSell|) * referencePrice},
 *       so resting orders count, not just fills;</li>
 *   <li>fail closed: a symbol with non-zero position or open orders and no
 *       finite positive reference price makes the exposure unmeasurable,
 *       and that alone trips the breaker ({@link #unpricedSymbols()});</li>
 *   <li>a breach calls {@link HftRiskGate#kill} on every gate, which makes each
 *       {@link HftOrderGateway} on those gates send a cancel-all.
 *       Recovery is hysteretic: trading resumes only once gross notional is
 *       below {@code cap * resumeFraction} and nothing is unpriced.</li>
 * </ul>
 *
 * <p>The detection latency is the poll interval: per-order checks stay in
 * the per-shard gates, and the firm-wide cap is a circuit breaker, not a
 * per-order gate.</p>
 *
 * <p>Construct with {@link #start}; the monitor thread is started after the
 * object is fully constructed.</p>
 */
public final class GlobalRiskAggregator implements AutoCloseable {

    /** Private monitor for lifecycle and registration; never exposed to callers. */
    private final Object lock = new Object();

    private final List<HftRiskGate> gates;
    private final double maxGrossNotional;
    private final double resumeNotional;
    private final long pollIntervalNanos;
    private Thread monitor;
    private volatile boolean running = true;
    private volatile boolean tripped;
    private volatile double lastGross;
    private volatile int lastUnpriced;
    private final AtomicLong trips = new AtomicLong();
    private boolean[] killedBeforeTrip;

    private GlobalRiskAggregator(List<HftRiskGate> gates, double maxGrossNotional,
                                 double resumeFraction, long pollIntervalNanos) {
        if (gates.isEmpty() || !(maxGrossNotional > 0)
                || !(resumeFraction > 0) || resumeFraction >= 1 || pollIntervalNanos <= 0) {
            throw new IllegalArgumentException(
                    "need gates, cap > 0, 0 < resumeFraction < 1, poll > 0");
        }
        this.gates = List.copyOf(gates);
        this.maxGrossNotional = maxGrossNotional;
        this.resumeNotional = maxGrossNotional * resumeFraction;
        this.pollIntervalNanos = pollIntervalNanos;
    }

    /**
     * Creates the aggregator and starts its monitor thread.
     *
     * @param gates             every shard's gate (see {@code ShardedTradingEngine.gates()})
     * @param maxGrossNotional  firm-wide cap on worst-case gross notional
     * @param resumeFraction    resume trading below cap times this (e.g. 0.9)
     * @param pollIntervalNanos monitor cadence (1_000_000 = 1 ms detection)
     */
    public static GlobalRiskAggregator start(List<HftRiskGate> gates, double maxGrossNotional,
                                             double resumeFraction, long pollIntervalNanos) {
        return start(gates, maxGrossNotional, resumeFraction, pollIntervalNanos,
                com.quantfinlib.util.ThreadPlacement.daemon("global-risk-aggregator"));
    }

    private final int[] unpricedScratch = new int[1];   // guarded by this

    /**
     * As {@link #start(List, double, double, long)}, creating the monitor
     * thread with {@code threadFactory} (naming, CPU placement).
     *
     * @param gates             every shard's gate
     * @param maxGrossNotional  firm-wide cap on worst-case gross notional
     * @param resumeFraction    resume trading below cap times this
     * @param pollIntervalNanos monitor cadence
     * @param threadFactory     creates the monitor thread
     * @return the running aggregator
     */
    public static GlobalRiskAggregator start(List<HftRiskGate> gates, double maxGrossNotional,
                                             double resumeFraction, long pollIntervalNanos,
                                             java.util.concurrent.ThreadFactory threadFactory) {
        GlobalRiskAggregator a = new GlobalRiskAggregator(gates, maxGrossNotional,
                resumeFraction, pollIntervalNanos);
        Thread t = threadFactory.newThread(a::monitorLoop);
        a.monitor = t;
        t.start();
        return a;
    }

    private void monitorLoop() {
        while (running) {
            sweep();
            LockSupport.parkNanos(pollIntervalNanos);
        }
    }

    /** One monitor step; also callable directly (tests, manual checks). */
    void sweep() {
        synchronized (lock) {
            int[] unpriced = unpricedScratch;   // reused: one sweep per poll, no garbage
            double gross = grossNotional(unpriced);
            lastGross = gross;
            lastUnpriced = unpriced[0];
            boolean breach = gross > maxGrossNotional || unpriced[0] > 0 || Double.isNaN(gross);
            if (!tripped && breach) {
                // Remember which gates were already killed (ops holds, another
                // breaker): recovery must not clear a kill this aggregator did
                // not set. A kill placed by someone else during our trip cannot
                // be told apart from ours; pre-existing holds are preserved.
                killedBeforeTrip = new boolean[gates.size()];
                for (int i = 0; i < gates.size(); i++) {
                    killedBeforeTrip[i] = gates.get(i).isKilled();
                    gates.get(i).kill(true);
                }
                tripped = true;
                trips.incrementAndGet();
            } else if (tripped && gross < resumeNotional && unpriced[0] == 0) {
                for (int i = 0; i < gates.size(); i++) {
                    if (!killedBeforeTrip[i]) {
                        gates.get(i).kill(false);
                    }
                }
                tripped = false;
            }
        }
    }

    /** Worst-case gross notional over every gate; unpriced exposed symbols excluded. */
    public double grossNotional() {
        synchronized (lock) {
            return grossNotional(unpricedScratch);
        }
    }

    private double grossNotional(int[] unpriced) {
        unpriced[0] = 0;
        double gross = 0;
        for (int g = 0; g < gates.size(); g++) {
            HftRiskGate gate = gates.get(g);
            int n = gate.symbolCapacity();
            for (int s = 0; s < n; s++) {
                long position = gate.position(s);
                long openBuy = gate.openQuantity(s, com.quantfinlib.orderbook.Side.BUY);
                long openSell = gate.openQuantity(s, com.quantfinlib.orderbook.Side.SELL);
                if (position == 0 && openBuy == 0 && openSell == 0) {
                    continue;
                }
                long worst = Math.max(Math.abs(position + openBuy),
                        Math.abs(position - openSell));
                double ref = gate.referencePrice(s);
                if (ref > 0 && ref != Double.POSITIVE_INFINITY) {
                    gross += worst * ref;
                } else if (worst != 0) {
                    unpriced[0]++;
                }
            }
        }
        return gross;
    }

    public boolean isTripped() {
        return tripped;
    }

    /** Gross notional from the most recent sweep. */
    public double lastGrossNotional() {
        return lastGross;
    }

    /** Exposed symbols without a usable reference price at the most recent sweep. */
    public int unpricedSymbols() {
        return lastUnpriced;
    }

    public long tripCount() {
        return trips.get();
    }

    /** Stops the monitor; gates keep whatever kill state they last had. */
    @Override
    public void close() {
        running = false;
        Thread t = monitor;
        if (t != null) {
            LockSupport.unpark(t);
            try {
                t.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
