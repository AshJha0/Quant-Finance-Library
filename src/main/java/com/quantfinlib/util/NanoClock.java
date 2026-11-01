package com.quantfinlib.util;

/**
 * Monotonic nanosecond time source for hot paths.
 *
 * <p>Components that stamp events take a clock instead of calling
 * {@link System#nanoTime()} themselves, read it once per event and pass the
 * value down, so a test can drive time explicitly and a deployment can swap
 * in a cheaper or shared source (a cached time updated by the polling
 * thread, for example). Values are only meaningful as differences.
 */
@FunctionalInterface
public interface NanoClock {

    /** {@link System#nanoTime()}. */
    NanoClock SYSTEM = System::nanoTime;

    /**
     * Current time.
     *
     * @return nanoseconds from an arbitrary origin
     */
    long nanoTime();
}
