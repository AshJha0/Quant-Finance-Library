package com.quantfinlib.util;

import java.util.concurrent.locks.LockSupport;

/**
 * jHiccup-style platform stall monitor: a daemon thread repeatedly parks for
 * a fixed resolution and records how much <b>longer than requested</b> each
 * park took. Any excess is a "hiccup" (GC pause, JVM safepoint, JIT
 * deoptimization stall, or OS scheduler preemption): exactly the pauses
 * that corrupt latency percentiles without showing up in application code.
 *
 * <p>Run one alongside a latency benchmark or a live session: if the
 * benchmark's p99.9 spikes while the hiccup monitor shows a matching stall,
 * the platform (not your code) ate the tail. Zero-dependency and
 * allocation-free while sampling ({@link LatencyRecorder} histogram).</p>
 *
 * <h2>Two sampling modes</h2>
 * <ul>
 *   <li><b>Park</b> (default constructor, {@link #HiccupMonitor(long)}): park
 *       for the resolution and record the overshoot. Cheap, but on Windows
 *       {@code parkNanos} wakes on the system timer tick (1 to 15.6 ms), so
 *       most of what it records there is timer quantisation, not platform
 *       stalls; read Windows figures from this mode as an upper bound.</li>
 *   <li><b>Spin</b> ({@link #spinning(long)}): busy-read {@code nanoTime()}
 *       and, per resolution window, record the largest gap between
 *       consecutive readings minus the clock step. No timer is involved, so
 *       it measures only time the thread was not running (safepoints, GC,
 *       preemption). It occupies one core while running.</li>
 * </ul>
 *
 * <p>See {@code docs/ULTRA_LOW_LATENCY.md} for the JVM/kernel tuning that
 * shrinks what this monitor observes.</p>
 */
public final class HiccupMonitor implements AutoCloseable {

    private final long resolutionNanos;
    private final boolean spin;
    private final LatencyRecorder recorder = new LatencyRecorder();
    private final Thread thread;
    private volatile boolean running;

    /** Default 1 ms sampling resolution. */
    public HiccupMonitor() {
        this(1_000_000L);
    }

    public HiccupMonitor(long resolutionNanos) {
        this(resolutionNanos, false);
    }

    /** Spin-sampling monitor (see the class notes); occupies one core while running. */
    public static HiccupMonitor spinning(long resolutionNanos) {
        return new HiccupMonitor(resolutionNanos, true);
    }

    private HiccupMonitor(long resolutionNanos, boolean spin) {
        if (resolutionNanos < 100_000) {
            throw new IllegalArgumentException("resolution below 100us measures spin noise, not hiccups");
        }
        this.resolutionNanos = resolutionNanos;
        this.spin = spin;
        this.thread = new Thread(spin ? this::spinLoop : this::sampleLoop, "hiccup-monitor");
        thread.setDaemon(true);
    }

    public HiccupMonitor start() {
        running = true;
        thread.start();
        return this;
    }

    public void stop() {
        running = false;
    }

    @Override
    public void close() {
        stop();
    }

    /** The hiccup histogram: excess-over-requested park time, in nanos. */
    public LatencyRecorder recorder() {
        return recorder;
    }

    public long samples() {
        return recorder.count();
    }

    /** Worst platform stall observed, in nanoseconds. */
    public long maxHiccupNanos() {
        return recorder.max();
    }

    public String summary() {
        return "platform hiccups (" + (spin ? "spin" : "park") + ", res="
                + resolutionNanos / 1_000_000.0 + "ms): " + recorder.summary();
    }

    private void sampleLoop() {
        while (running) {
            long before = System.nanoTime();
            LockSupport.parkNanos(resolutionNanos);
            long excess = System.nanoTime() - before - resolutionNanos;
            recorder.record(Math.max(0, excess));
        }
    }

    private void spinLoop() {
        long step = LatencyRecorder.clockResolutionNanos();
        while (running) {
            long windowStart = System.nanoTime();
            long prev = windowStart;
            long worstGap = 0;
            long now;
            do {
                now = System.nanoTime();
                long gap = now - prev;
                if (gap > worstGap) {
                    worstGap = gap;
                }
                prev = now;
            } while (now - windowStart < resolutionNanos);
            recorder.record(Math.max(0, worstGap - step));
        }
    }
}
