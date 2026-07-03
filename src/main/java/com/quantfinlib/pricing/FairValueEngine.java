package com.quantfinlib.pricing;

/**
 * Latency-adjusted fair value for rapidly updating order books. Maintains the
 * size-weighted microprice plus a short-window mid drift estimate, so a
 * consumer that is {@code latencyNanos} behind the market can project the
 * "true mid" at the moment its order would actually arrive.
 *
 * <p>Zero allocation after construction (fixed ring of samples); single
 * writer.</p>
 */
public final class FairValueEngine {

    private final double[] mids;
    private final long[] times;
    private final int mask;
    private final long windowNanos;
    private long head;                 // number of samples ever written
    private double microprice = Double.NaN;

    /**
     * @param capacity    ring capacity (rounded up to a power of two)
     * @param windowNanos lookback window for the drift estimate
     */
    public FairValueEngine(int capacity, long windowNanos) {
        int cap = Integer.highestOneBit(Math.max(2, capacity - 1)) * 2;
        this.mids = new double[cap];
        this.times = new long[cap];
        this.mask = cap - 1;
        this.windowNanos = windowNanos;
    }

    /** 256-sample ring over a 500 ms drift window. */
    public FairValueEngine() {
        this(256, 500_000_000L);
    }

    /** Feed a top-of-book update. */
    public void onQuote(double bid, double ask, double bidSize, double askSize, long timestampNanos) {
        this.microprice = microprice(bid, ask, bidSize, askSize);
        int i = (int) (head & mask);
        mids[i] = (bid + ask) / 2;
        times[i] = timestampNanos;
        head++;
    }

    /** Size-weighted microprice: {@code I*ask + (1-I)*bid}, {@code I = bidSize/(bidSize+askSize)}. */
    public static double microprice(double bid, double ask, double bidSize, double askSize) {
        double total = bidSize + askSize;
        if (total <= 0 || Double.isNaN(bid) || Double.isNaN(ask)) {
            return Double.NaN;
        }
        double i = bidSize / total;
        return i * ask + (1 - i) * bid;
    }

    /** Latest microprice (NaN before the first quote). */
    public double microprice() {
        return microprice;
    }

    /** Estimated mid drift in price units per second over the lookback window. */
    public double driftPerSecond() {
        if (head < 2) {
            return 0;
        }
        int newest = (int) ((head - 1) & mask);
        long cutoff = times[newest] - windowNanos;
        long oldestSeq = Math.max(0, head - mids.length);
        // Walk back to the oldest sample still inside the window.
        int oldest = newest;
        for (long seq = head - 2; seq >= oldestSeq; seq--) {
            int i = (int) (seq & mask);
            if (times[i] < cutoff) {
                break;
            }
            oldest = i;
        }
        long dt = times[newest] - times[oldest];
        if (dt <= 0) {
            return 0;
        }
        return (mids[newest] - mids[oldest]) / (dt / 1e9);
    }

    /**
     * Fair price projected {@code latencyNanos} into the future:
     * microprice plus drift over the latency horizon.
     */
    public double latencyAdjustedFair(long latencyNanos) {
        return microprice + driftPerSecond() * latencyNanos / 1e9;
    }
}
