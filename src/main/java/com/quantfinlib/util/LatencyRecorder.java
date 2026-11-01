package com.quantfinlib.util;

import java.util.Locale;

/**
 * Zero-allocation nanosecond latency histogram with log-linear buckets in
 * the style of HdrHistogram (Tene, 2012): values below 128 ns are recorded
 * exactly; above that each power of two is split into 128 equal
 * sub-buckets, so a bucket is at most 1/128 (0.78 %) of its value wide.
 * {@link #record} is a few array writes and safe on the hot path.
 * Single-writer; not thread-safe.
 *
 * <h2>What the percentiles mean</h2>
 * <p>{@link #percentile} returns the highest value that falls in the bucket
 * containing the requested rank, clamped to the observed min and max, so it
 * is an upper bound on the true sample quantile, never a bucket midpoint
 * dressed up as a measurement. {@link #percentileLowerBound} gives the
 * other edge. Neither can be more precise than the clock: on Windows
 * {@code System.nanoTime()} advances in 100 ns steps (a 10 MHz QPC), so a
 * reported 100 ns means one clock tick, not 100 ns of work.
 * {@link #clockResolutionNanos()} estimates the step and {@link #summary()}
 * prints it next to the numbers.</p>
 */
public final class LatencyRecorder {

    private static final int SUB_BITS = 7;
    private static final int SUB = 1 << SUB_BITS;

    private final long[] counts = new long[(64 - SUB_BITS + 1) * SUB];
    private long count;
    private long sum;
    private long max;
    private long min = Long.MAX_VALUE;

    private static volatile long clockResolution = -1;

    public void record(long nanos) {
        if (nanos < 0) {
            nanos = 0;
        }
        count++;
        sum += nanos;
        if (nanos > max) {
            max = nanos;
        }
        if (nanos < min) {
            min = nanos;
        }
        counts[bucketIndex(nanos)]++;
    }

    static int bucketIndex(long v) {
        if (v < SUB) {
            return (int) v;
        }
        int exp = 63 - Long.numberOfLeadingZeros(v);           // >= SUB_BITS
        int sub = (int) ((v >>> (exp - SUB_BITS)) & (SUB - 1));
        return (exp - SUB_BITS + 1) * SUB + sub;
    }

    /** Smallest value mapping to bucket {@code index}. */
    static long bucketLow(int index) {
        if (index < SUB) {
            return index;
        }
        int exp = (index >>> SUB_BITS) + SUB_BITS - 1;
        int sub = index & (SUB - 1);
        return (1L << exp) + ((long) sub << (exp - SUB_BITS));
    }

    /** Largest value mapping to bucket {@code index}. */
    static long bucketHigh(int index) {
        if (index < SUB) {
            return index;
        }
        int exp = (index >>> SUB_BITS) + SUB_BITS - 1;
        return bucketLow(index) + (1L << (exp - SUB_BITS)) - 1;
    }

    private int bucketOfRank(double p) {
        long target = (long) Math.ceil(p * count);
        if (target <= 0) {
            target = 1;
        }
        long cumulative = 0;
        for (int i = 0; i < counts.length; i++) {
            cumulative += counts[i];
            if (cumulative >= target) {
                return i;
            }
        }
        return counts.length - 1;
    }

    /**
     * Upper bound on the latency at quantile {@code p} in [0, 1], in
     * nanoseconds: the top of the bucket holding rank {@code ceil(p * n)},
     * clamped to [min, max]. Exact below 128 ns.
     */
    public long percentile(double p) {
        if (count == 0) {
            return 0;
        }
        long est = bucketHigh(bucketOfRank(p));
        return Math.max(min, Math.min(max, est));
    }

    /** Lower edge of the same bucket as {@link #percentile}, clamped to [min, max]. */
    public long percentileLowerBound(double p) {
        if (count == 0) {
            return 0;
        }
        long est = bucketLow(bucketOfRank(p));
        return Math.max(min, Math.min(max, est));
    }

    public long count()  { return count; }
    public long max()    { return max; }
    public long min()    { return count == 0 ? 0 : min; }
    public double mean() { return count == 0 ? 0 : (double) sum / count; }

    public void reset() {
        java.util.Arrays.fill(counts, 0);
        count = 0;
        sum = 0;
        max = 0;
        min = Long.MAX_VALUE;
    }

    /**
     * Estimated step of {@code System.nanoTime()} on this machine: the
     * smallest non-zero difference between consecutive readings over a short
     * spin. Measured once and cached.
     */
    public static long clockResolutionNanos() {
        long r = clockResolution;
        if (r < 0) {
            long best = Long.MAX_VALUE;
            for (int i = 0; i < 200_000; i++) {
                long a = System.nanoTime();
                long b;
                do {
                    b = System.nanoTime();
                } while (b == a);
                best = Math.min(best, b - a);
            }
            clockResolution = r = best;
        }
        return r;
    }

    /**
     * One-line summary: count, mean, percentile upper bounds and max, with
     * the clock resolution so a reader can tell measurement from
     * quantisation.
     */
    public String summary() {
        return String.format(Locale.ROOT,
                "n=%d mean=%s p50<=%s p90<=%s p99<=%s p99.9<=%s max=%s (clock step ~%dns)",
                count, fmt((long) mean()), fmt(percentile(0.50)), fmt(percentile(0.90)),
                fmt(percentile(0.99)), fmt(percentile(0.999)), fmt(max), clockResolutionNanos());
    }

    private static String fmt(long nanos) {
        if (nanos < 1_000) {
            return nanos + "ns";
        }
        if (nanos < 1_000_000) {
            return String.format(Locale.ROOT, "%.2fus", nanos / 1_000.0);
        }
        return String.format(Locale.ROOT, "%.3fms", nanos / 1_000_000.0);
    }
}
