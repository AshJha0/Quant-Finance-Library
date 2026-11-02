/**
 * Latency measurement and threading helpers for the trading lane:
 * {@link com.quantfinlib.util.LatencyRecorder} (zero-allocation log-linear
 * nanosecond histogram), {@link com.quantfinlib.util.HiccupMonitor}
 * (jHiccup-style platform stall attribution: GC, safepoint and scheduler
 * pauses versus code latency), {@link com.quantfinlib.util.IdleStrategy},
 * {@link com.quantfinlib.util.NanoClock} and
 * {@link com.quantfinlib.util.ThreadPlacement}.
 *
 * <p>Since 3.0 this package is in the artifact
 * {@code quant-finance-library-lowlatency}. {@code MathUtils} was removed
 * (use {@code com.quantfinlib.stats}); {@code ValueArrays} and
 * {@code Immutable} left the public API for the main module's internal
 * package. See docs/MIGRATION_3.0.md.</p>
 */
package com.quantfinlib.util;
