package com.quantfinlib.alloc;

import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Measures heap allocation of an operation on the current thread with
 * {@code com.sun.management.ThreadMXBean.getCurrentThreadAllocatedBytes}.
 *
 * <p>Method: run the operation {@link #WARMUP} times so the JIT compiles it
 * (and escape analysis has run), then measure {@link #MEASURED} calls. A run
 * passes when the measured batch allocated at most {@link #TOLERANCE_BYTES}.
 * Up to {@link #ATTEMPTS} batches are tried and the best one counts, so a
 * one-off event in a batch (a deoptimisation that materialises scalar-replaced
 * objects, a late compilation, a class initialiser) does not fail the test.</p>
 *
 * <p>Why the tolerance is safe: the smallest heap object is 16 bytes, so an
 * operation that allocates on every call puts at least
 * {@code 16 * MEASURED = 160,000} bytes into every batch, about 600 times the
 * tolerance. The test can only pass for code that allocates nothing per call
 * in steady state. The check counts bytes, not time, so it is not
 * timing-sensitive and runs in the default build.</p>
 */
final class AllocationProbe {

    /** Calls before measuring. */
    static final int WARMUP = 50_000;
    /** Calls per measured batch. */
    static final int MEASURED = 10_000;
    /** Measured batches tried; the smallest counts. */
    static final int ATTEMPTS = 5;
    /** Bytes a batch may allocate and still pass (see the class comment). */
    static final long TOLERANCE_BYTES = 256;

    /** One call of the operation under test; {@code i} lets it vary its input. */
    @FunctionalInterface
    interface Op {
        /**
         * Runs the operation once.
         *
         * @param i call index
         * @return any value derived from the result, so the JIT cannot drop the call
         * @throws Exception if the operation throws
         */
        double run(int i) throws Exception;
    }

    private static volatile double sink;

    private AllocationProbe() {
    }

    private static com.sun.management.ThreadMXBean bean() {
        java.lang.management.ThreadMXBean b = ManagementFactory.getThreadMXBean();
        assumeTrue(b instanceof com.sun.management.ThreadMXBean, "no com.sun.management.ThreadMXBean");
        com.sun.management.ThreadMXBean t = (com.sun.management.ThreadMXBean) b;
        assumeTrue(t.isThreadAllocatedMemorySupported(), "thread allocation counting unsupported");
        if (!t.isThreadAllocatedMemoryEnabled()) {
            t.setThreadAllocatedMemoryEnabled(true);
        }
        return t;
    }

    /**
     * Smallest number of bytes allocated by one measured batch.
     *
     * @param op the operation
     * @return bytes allocated by the best batch
     * @throws Exception if the operation throws
     */
    static long bestBatchBytes(Op op) throws Exception {
        com.sun.management.ThreadMXBean t = bean();
        double acc = 0;
        for (int i = 0; i < WARMUP; i++) {
            acc += op.run(i);
        }
        long best = Long.MAX_VALUE;
        for (int a = 0; a < ATTEMPTS && best > TOLERANCE_BYTES; a++) {
            long before = t.getCurrentThreadAllocatedBytes();
            for (int i = 0; i < MEASURED; i++) {
                acc += op.run(i);
            }
            long used = t.getCurrentThreadAllocatedBytes() - before;
            best = Math.min(best, used);
        }
        sink = acc;
        return best;
    }

    /**
     * Fails unless {@code op} allocates nothing per call in steady state.
     *
     * @param claim the javadoc claim under test, quoted in the failure
     * @param op    the operation
     * @throws Exception if the operation throws
     */
    static void assertAllocationFree(String claim, Op op) throws Exception {
        long best = bestBatchBytes(op);
        if (best > TOLERANCE_BYTES) {
            fail(claim + ": best of " + ATTEMPTS + " batches of " + MEASURED + " calls allocated "
                    + best + " bytes (" + (best / (double) MEASURED) + " per call), tolerance "
                    + TOLERANCE_BYTES);
        }
    }

    /**
     * Fails unless {@code op} allocates at least once per call: proves the probe sees allocation.
     *
     * @param op the operation
     * @throws Exception if the operation throws
     */
    static void assertAllocates(Op op) throws Exception {
        long best = bestBatchBytes(op);
        if (best < 16L * MEASURED) {
            fail("expected at least 16 bytes per call, best batch allocated " + best);
        }
    }

    static double sink() {
        return sink;
    }
}
