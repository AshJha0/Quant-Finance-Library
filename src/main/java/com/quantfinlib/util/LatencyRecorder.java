package com.quantfinlib.util;

import java.util.Locale;

/**
 * Zero-allocation nanosecond latency histogram (HdrHistogram-style
 * log-linear buckets: 16 sub-buckets per power of two, ~6% worst-case
 * quantile error). {@link #record} is a couple of array writes — safe to call
 * on the hot path. Single-writer; not thread-safe.
 */
public final class LatencyRecorder {

    private static final int SUB_BITS = 4;
    private static final int SUB = 1 << SUB_BITS;

    private final long[] counts = new long[64 * SUB];
    private long count;
    private long sum;
    private long max;
    private long min = Long.MAX_VALUE;

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
        int exp = 63 - Long.numberOfLeadingZeros(v);
        int sub = (int) ((v >>> (exp - SUB_BITS)) & (SUB - 1));
        return exp * SUB + sub;
    }

    private static long bucketMidpoint(int index) {
        if (index < SUB) {
            return index;
        }
        int exp = index / SUB;
        int sub = index % SUB;
        long low = (1L << exp) + ((long) sub << (exp - SUB_BITS));
        long width = 1L << (exp - SUB_BITS);
        return low + width / 2;
    }

    /** Estimated latency at quantile {@code p} in [0, 1], in nanoseconds. */
    public long percentile(double p) {
        if (count == 0) {
            return 0;
        }
        long target = (long) Math.ceil(p * count);
        if (target <= 0) {
            target = 1;
        }
        long cumulative = 0;
        for (int i = 0; i < counts.length; i++) {
            cumulative += counts[i];
            if (cumulative >= target) {
                long est = bucketMidpoint(i);
                return Math.max(min, Math.min(max, est));
            }
        }
        return max;
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

    /** One-line summary: p50/p90/p99/p99.9/max in appropriate units. */
    public String summary() {
        return String.format(Locale.ROOT,
                "n=%d mean=%s p50=%s p90=%s p99=%s p99.9=%s max=%s",
                count, fmt((long) mean()), fmt(percentile(0.50)), fmt(percentile(0.90)),
                fmt(percentile(0.99)), fmt(percentile(0.999)), fmt(max));
    }

    private static String fmt(long nanos) {
        if (nanos < 1_000) {
            return nanos + "ns";
        }
        if (nanos < 1_000_000) {
            return String.format(Locale.ROOT, "%.1fus", nanos / 1_000.0);
        }
        return String.format(Locale.ROOT, "%.2fms", nanos / 1_000_000.0);
    }
}
