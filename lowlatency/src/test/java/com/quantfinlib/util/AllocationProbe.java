package com.quantfinlib.util;

import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Test helper for zero-allocation claims: runs a measured block on the
 * current thread and requires the thread's allocated-bytes counter to be
 * unchanged, exactly. JIT compilation, class loading or a deoptimisation
 * can allocate during an early window, so the block is retried a bounded
 * number of times; one clean window proves the steady state allocates
 * nothing, and a block that allocates on every pass fails every attempt.
 */
public final class AllocationProbe {

    private AllocationProbe() {
    }

    /**
     * Asserts that the measured action allocates nothing.
     *
     * @param warmup   run once before measuring (JIT warm-up)
     * @param measured the block that must allocate nothing
     * @param attempts measured windows to try before failing
     */
    public static void assertZeroAllocation(Runnable warmup, Runnable measured, int attempts) {
        var mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assumeTrue(mx.isThreadAllocatedMemorySupported(), "allocation counter unsupported");
        mx.setThreadAllocatedMemoryEnabled(true);
        long tid = Thread.currentThread().threadId();
        warmup.run();
        long last = -1;
        for (int i = 0; i < attempts; i++) {
            long before = mx.getThreadAllocatedBytes(tid);
            measured.run();
            last = mx.getThreadAllocatedBytes(tid) - before;
            if (last == 0) {
                return;
            }
        }
        fail("allocated " + last + " bytes in the last of " + attempts + " measured windows");
    }
}
