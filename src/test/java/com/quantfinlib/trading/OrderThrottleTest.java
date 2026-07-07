package com.quantfinlib.trading;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderThrottleTest {

    private static final long MS = 1_000_000L;

    @Test
    void burstThenSustainedRate() {
        OrderThrottle t = new OrderThrottle(1_000, 5);   // 1k/s, burst 5
        long now = 0;
        for (int i = 0; i < 5; i++) {
            assertTrue(t.tryAcquire(now), "burst permit " + i);
        }
        assertFalse(t.tryAcquire(now), "bucket exhausted");
        // 1 ms refills exactly one token at 1k/s.
        assertTrue(t.tryAcquire(now + MS));
        assertFalse(t.tryAcquire(now + MS));
        assertEquals(6, t.acquiredCount());
        assertEquals(2, t.throttledCount());
    }

    @Test
    void bucketNeverBanksMoreThanTheBurst() {
        OrderThrottle t = new OrderThrottle(1_000, 3);
        long idle = 10_000 * MS;                          // 10 s idle
        int granted = 0;
        while (t.tryAcquire(idle)) {
            granted++;
        }
        assertEquals(3, granted, "idle time must not bank beyond the burst");
    }

    @Test
    void nanosUntilAvailableIsAnExactWaitHint() {
        OrderThrottle t = new OrderThrottle(100, 1);      // 10 ms per token
        assertTrue(t.tryAcquire(0));
        long wait = t.nanosUntilAvailable(0);
        assertTrue(wait > 0);
        assertFalse(t.tryAcquire(wait - 1_000));
        assertTrue(t.tryAcquire(wait));
    }

    @Test
    void throttleIsAllocationFree() {
        OrderThrottle t = new OrderThrottle(1_000_000, 64);
        long now = 0;
        for (int i = 0; i < 200_000; i++) {           // warm-up
            now += 500;
            t.tryAcquire(now);
            t.nanosUntilAvailable(now);
        }
        var mx = (com.sun.management.ThreadMXBean)
                java.lang.management.ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long before = mx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < 500_000; i++) {
            now += 500;
            t.tryAcquire(now);
            t.nanosUntilAvailable(now);
        }
        long allocated = mx.getThreadAllocatedBytes(tid) - before;
        assertTrue(allocated < 100_000, "throttle allocated " + allocated + " bytes");
    }

    @Test
    void sustainedThroughputMatchesTheConfiguredRate() {
        OrderThrottle t = new OrderThrottle(50_000, 10);
        long granted = 0;
        // One send attempt every 10 µs for one simulated second.
        for (long now = 0; now < 1_000_000_000L; now += 10_000) {
            if (t.tryAcquire(now)) {
                granted++;
            }
        }
        assertTrue(Math.abs(granted - 50_000) <= 11,
                "granted " + granted + " permits at a 50k/s limit");
    }
}
