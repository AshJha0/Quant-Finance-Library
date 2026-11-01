package com.quantfinlib.util;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LatencyRecorderTest {

    @Test
    void valuesBelow128AreExact() {
        LatencyRecorder r = new LatencyRecorder();
        for (int v = 0; v < 128; v++) {
            r.record(v);
        }
        // Rank ceil(0.5 * 128) = 64 -> value 63, exactly.
        assertEquals(63, r.percentile(0.5));
        assertEquals(63, r.percentileLowerBound(0.5));
        assertEquals(127, r.percentile(1.0));
    }

    @Test
    void bucketsAreAtMostOneOver128Wide() {
        for (long v : new long[]{128, 129, 255, 256, 1_000, 4_095, 123_456_789L,
                Long.MAX_VALUE / 3}) {
            int i = LatencyRecorder.bucketIndex(v);
            long lo = LatencyRecorder.bucketLow(i);
            long hi = LatencyRecorder.bucketHigh(i);
            assertTrue(lo <= v && v <= hi, v + " outside [" + lo + ", " + hi + "]");
            assertTrue(hi - lo + 1 <= Math.max(1, lo / 128), "bucket too wide at " + v);
        }
    }

    /**
     * Against a brute-force sorted sample: the reported percentile must be
     * an upper bound on the true order statistic, the lower bound must not
     * exceed it, and both lie within one bucket (0.78 %) of it.
     */
    @Test
    void percentilesBracketTheExactOrderStatistic() {
        SplittableRandom rnd = new SplittableRandom(20261101L);
        long[] xs = new long[100_000];
        LatencyRecorder r = new LatencyRecorder();
        for (int i = 0; i < xs.length; i++) {
            // Log-uniform over 50 ns .. 5 ms, like real latency data.
            xs[i] = (long) Math.exp(Math.log(50) + rnd.nextDouble() * Math.log(100_000));
            r.record(xs[i]);
        }
        Arrays.sort(xs);
        for (double p : new double[]{0.5, 0.9, 0.99, 0.999}) {
            long exact = xs[(int) Math.ceil(p * xs.length) - 1];
            long hi = r.percentile(p);
            long lo = r.percentileLowerBound(p);
            assertTrue(lo <= exact && exact <= hi, p + ": " + lo + " <= " + exact + " <= " + hi);
            assertTrue(hi - exact <= Math.max(1, exact / 128), "upper bound too loose at " + p);
        }
        assertEquals(xs[0], r.min());
        assertEquals(xs[xs.length - 1], r.max());
    }

    @Test
    void neverReportsAMidpointAboveTheMax() {
        LatencyRecorder r = new LatencyRecorder();
        for (int i = 0; i < 10; i++) {
            r.record(1_000);
        }
        // 1000 falls in [1000, 1003]; the upper bound is clamped to the max seen.
        assertEquals(1_000, r.percentile(0.5));
    }

    @Test
    void resetAndSummary() {
        LatencyRecorder r = new LatencyRecorder();
        r.record(-5);
        r.record(500);
        r.record(2_500_000);
        String s = r.summary();
        assertTrue(s.contains("n=3"));
        assertTrue(s.contains("ms"));
        assertTrue(s.contains("clock step"), s);
        assertTrue(LatencyRecorder.clockResolutionNanos() > 0);
        r.reset();
        assertEquals(0, r.count());
        assertEquals(0, r.max());
    }
}
