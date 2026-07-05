package com.quantfinlib.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HiccupMonitorTest {

    @Test
    void samplesPlatformStallsContinuously() throws Exception {
        try (HiccupMonitor monitor = new HiccupMonitor(1_000_000).start()) {
            Thread.sleep(400);
            // Sample count is platform-dependent: Windows timer coarseness
            // stretches a 1ms park to ~15ms — which is itself the hiccup the
            // monitor measures. Just require continuous sampling.
            assertTrue(monitor.samples() > 10, "samples=" + monitor.samples());
            assertTrue(monitor.maxHiccupNanos() >= 0);
            // Even a coarse scheduler should park within ~100ms of the request.
            assertTrue(monitor.recorder().percentile(0.5) < 100_000_000L,
                    "median hiccup " + monitor.recorder().percentile(0.5) + "ns");
            assertTrue(monitor.summary().contains("platform hiccups"));
        }
    }

    @Test
    void stopIsIdempotentAndCloseable() throws Exception {
        HiccupMonitor monitor = new HiccupMonitor().start();
        Thread.sleep(50);
        monitor.stop();
        long samples = monitor.samples();
        monitor.stop();
        monitor.close();
        Thread.sleep(50);
        // No further sampling after stop (one in-flight park may complete).
        assertTrue(monitor.samples() <= samples + 1);
    }

    @Test
    void rejectsSpinNoiseResolutions() {
        assertThrows(IllegalArgumentException.class, () -> new HiccupMonitor(1_000));
    }
}
