package com.quantfinlib.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lifecycle of {@link HiccupMonitor} without wall-clock assertions, so it runs
 * in the default (untagged) test set. {@code HiccupMonitorTest}, tagged
 * {@code timing}, checks the measured stall sizes. Each case only waits until
 * the sampler has produced its first samples; the suite timeout bounds the wait.
 */
class HiccupMonitorLifecycleTest {

    private static void awaitSamples(HiccupMonitor m, long atLeast) throws InterruptedException {
        while (m.samples() < atLeast) {
            Thread.sleep(1);
        }
    }

    @Test
    void parkingMonitorSamplesStopsAndSummarises() throws Exception {
        HiccupMonitor m = new HiccupMonitor(1_000_000).start();
        try {
            awaitSamples(m, 3);
        } finally {
            m.close();
        }
        long after = m.samples();
        assertTrue(after >= 3);
        assertTrue(m.maxHiccupNanos() >= 0);
        String s = m.summary();
        assertTrue(s.contains("platform hiccups"), s);
        assertTrue(s.contains("(park, res=1.0ms)"), s);
        m.stop();   // idempotent
    }

    @Test
    void spinningMonitorSamplesAndSaysSo() throws Exception {
        try (HiccupMonitor m = HiccupMonitor.spinning(1_000_000).start()) {
            awaitSamples(m, 3);
            assertTrue(m.summary().contains("(spin, res=1.0ms)"), m.summary());
        }
    }

    @Test
    void defaultConstructorStartsAndStops() throws Exception {
        try (HiccupMonitor m = new HiccupMonitor().start()) {
            awaitSamples(m, 1);
        }
    }

    @Test
    void rejectsResolutionsBelowTheSpinNoiseFloor() {
        assertThrows(IllegalArgumentException.class, () -> new HiccupMonitor(1_000));
        assertThrows(IllegalArgumentException.class, () -> HiccupMonitor.spinning(1_000));
    }
}
