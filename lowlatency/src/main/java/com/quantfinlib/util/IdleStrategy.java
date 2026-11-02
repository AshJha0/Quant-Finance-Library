package com.quantfinlib.util;

import java.util.concurrent.locks.LockSupport;

/**
 * What a polling thread does when a poll found no work.
 *
 * <p>Every consumer loop in the library (market-data bus, order gateway,
 * capture writer, feed retry) polls a ring and, when the ring is empty, asks
 * its idle strategy to wait. The choice trades latency against CPU:
 * <ul>
 *   <li>{@link #busySpin()}: {@link Thread#onSpinWait()} only. Lowest wake-up
 *       latency; burns a whole core. Use with an isolated core (see
 *       {@link ThreadPlacement}).</li>
 *   <li>{@link #yielding()}: {@link Thread#yield()}. Gives the core to another
 *       runnable thread, if any.</li>
 *   <li>{@link #parking(long)}: a fixed {@link LockSupport#parkNanos(long)}.
 *       The OS timer slack (about 50 microseconds on default Linux) sets the
 *       real sleep, whatever the argument.</li>
 *   <li>{@link #backoff(int, int, long, long)}: spin, then yield, then park
 *       with a period that doubles up to a cap. Fast after a short gap, cheap
 *       after a long one.</li>
 * </ul>
 *
 * <p>Implementations may keep state (the back-off counters), so one instance
 * belongs to one thread. {@link #idle(int)} with a positive work count resets
 * that state; the loop calls it on every pass:
 * <pre>{@code
 * while (running) {
 *     idle.idle(ring.drainTo(handler, 256));
 * }
 * }</pre>
 * The idle path allocates nothing.
 */
public interface IdleStrategy {

    /**
     * Called after each poll. Does nothing when {@code workCount > 0} other
     * than resetting any back-off; waits according to the strategy otherwise.
     *
     * @param workCount items processed by the poll that just finished
     */
    default void idle(int workCount) {
        if (workCount > 0) {
            reset();
        } else {
            idle();
        }
    }

    /** Waits once, advancing any back-off. */
    void idle();

    /** Clears back-off state; the next {@link #idle()} starts from the cheapest wait. */
    default void reset() {
    }

    /**
     * Spin with {@link Thread#onSpinWait()}.
     *
     * @return a stateless busy-spin strategy
     */
    static IdleStrategy busySpin() {
        return BusySpin.INSTANCE;
    }

    /**
     * {@link Thread#yield()} on every idle pass.
     *
     * @return a stateless yielding strategy
     */
    static IdleStrategy yielding() {
        return Yielding.INSTANCE;
    }

    /**
     * Park for a fixed period on every idle pass.
     *
     * @param nanos park period, at least 1
     * @return a stateless parking strategy
     */
    static IdleStrategy parking(long nanos) {
        return new Parking(nanos);
    }

    /**
     * Spin {@code maxSpins} times, then yield {@code maxYields} times, then
     * park starting at {@code minParkNanos} and doubling to {@code maxParkNanos}.
     *
     * @param maxSpins     spin passes before yielding, at least 0
     * @param maxYields    yield passes before parking, at least 0
     * @param minParkNanos first park period, at least 1
     * @param maxParkNanos park period cap, at least {@code minParkNanos}
     * @return a new back-off strategy (stateful: one per thread)
     */
    static IdleStrategy backoff(int maxSpins, int maxYields, long minParkNanos, long maxParkNanos) {
        return new Backoff(maxSpins, maxYields, minParkNanos, maxParkNanos);
    }

    /** Busy spin. */
    final class BusySpin implements IdleStrategy {
        static final BusySpin INSTANCE = new BusySpin();

        private BusySpin() {
        }

        @Override
        public void idle() {
            Thread.onSpinWait();
        }
    }

    /** Yield. */
    final class Yielding implements IdleStrategy {
        static final Yielding INSTANCE = new Yielding();

        private Yielding() {
        }

        @Override
        public void idle() {
            Thread.yield();
        }
    }

    /** Fixed park. */
    final class Parking implements IdleStrategy {
        private final long nanos;

        Parking(long nanos) {
            if (nanos < 1) {
                throw new IllegalArgumentException("park period must be >= 1 ns");
            }
            this.nanos = nanos;
        }

        @Override
        public void idle() {
            LockSupport.parkNanos(nanos);
        }

        /**
         * The park period.
         *
         * @return nanoseconds
         */
        public long parkNanos() {
            return nanos;
        }
    }

    /** Spin, then yield, then park with exponential back-off. Single-thread state. */
    final class Backoff implements IdleStrategy {
        private final int maxSpins;
        private final int maxYields;
        private final long minParkNanos;
        private final long maxParkNanos;
        private int spins;
        private int yields;
        private long parkNanos;

        Backoff(int maxSpins, int maxYields, long minParkNanos, long maxParkNanos) {
            if (maxSpins < 0 || maxYields < 0 || minParkNanos < 1 || maxParkNanos < minParkNanos) {
                throw new IllegalArgumentException("invalid back-off parameters");
            }
            this.maxSpins = maxSpins;
            this.maxYields = maxYields;
            this.minParkNanos = minParkNanos;
            this.maxParkNanos = maxParkNanos;
            this.parkNanos = minParkNanos;
        }

        @Override
        public void idle() {
            if (spins < maxSpins) {
                spins++;
                Thread.onSpinWait();
            } else if (yields < maxYields) {
                yields++;
                Thread.yield();
            } else {
                LockSupport.parkNanos(parkNanos);
                parkNanos = Math.min(parkNanos << 1, maxParkNanos);
            }
        }

        @Override
        public void reset() {
            spins = 0;
            yields = 0;
            parkNanos = minParkNanos;
        }

        /**
         * The period the next park would use (for tests and monitoring).
         *
         * @return nanoseconds
         */
        public long currentParkNanos() {
            return parkNanos;
        }
    }
}
