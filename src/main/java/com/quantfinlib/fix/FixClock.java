package com.quantfinlib.fix;

/**
 * Time source for a {@link FixSession}: a monotonic clock for heartbeat and
 * timeout arithmetic and a wall clock for SendingTime(52). Injecting one
 * makes the heartbeat state machine testable without sleeping: advance a
 * manual clock, call {@link FixSession#onTimer()}, assert.
 */
public interface FixClock {

    /** Monotonic nanoseconds, arbitrary origin (as {@link System#nanoTime()}). */
    long nanoTime();

    /** Wall-clock milliseconds since the Unix epoch, UTC. */
    long currentTimeMillis();

    /** The system clocks. */
    static FixClock system() {
        return SystemClock.INSTANCE;
    }

    /** System clock singleton. */
    enum SystemClock implements FixClock {
        INSTANCE;

        @Override
        public long nanoTime() {
            return System.nanoTime();
        }

        @Override
        public long currentTimeMillis() {
            return System.currentTimeMillis();
        }
    }
}
