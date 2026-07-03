package com.quantfinlib.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LatencyRecorderTest {

    @Test
    void percentilesWithinBucketResolution() {
        LatencyRecorder r = new LatencyRecorder();
        for (long v = 1; v <= 10_000; v++) {
            r.record(v);
        }
        assertEquals(10_000, r.count());
        assertEquals(1, r.min());
        assertEquals(10_000, r.max());
        assertEquals(5_000.5, r.mean(), 0.01);
        // Log-linear buckets: ~6-7% worst-case quantile error.
        assertEquals(5_000, r.percentile(0.50), 5_000 * 0.07);
        assertEquals(9_900, r.percentile(0.99), 9_900 * 0.07);
        assertTrue(r.percentile(0.999) <= r.max());
    }

    @Test
    void smallExactValuesAndReset() {
        LatencyRecorder r = new LatencyRecorder();
        for (int i = 0; i < 10; i++) {
            r.record(7);
        }
        assertEquals(7, r.percentile(0.5));   // values < 16 are bucketed exactly
        assertEquals(7, r.max());
        r.reset();
        assertEquals(0, r.count());
        assertEquals(0, r.max());
    }

    @Test
    void negativeClampedAndSummaryFormats() {
        LatencyRecorder r = new LatencyRecorder();
        r.record(-5);
        r.record(500);
        r.record(2_500_000);
        String s = r.summary();
        assertTrue(s.contains("n=3"));
        assertTrue(s.contains("ms"));         // 2.5ms max formatted in ms
    }
}
