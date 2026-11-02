/**
 * Latency measurement utilities and the deprecated numerics helper.
 * {@link com.quantfinlib.util.MathUtils} (deprecated for removal in 2.4.0;
 * every method delegates to {@code com.quantfinlib.stats}),
 * {@link com.quantfinlib.util.LatencyRecorder} (zero-allocation log-linear
 * nanosecond histogram) and {@link com.quantfinlib.util.HiccupMonitor}
 * (jHiccup-style platform stall attribution — GC/safepoint/scheduler pauses
 * versus code latency).
 *
 * <p><b>3.0 notice.</b> This package is split up in 3.0.
 * {@code MathUtils} is deprecated in 2.4.0 and removed in 3.0; use
 * {@code com.quantfinlib.stats} ({@code Stats}, {@code SpecialFunctions},
 * {@code LinearAlgebra}, {@code Numerics}). {@code LatencyRecorder},
 * {@code HiccupMonitor}, {@code IdleStrategy}, {@code NanoClock} and
 * {@code ThreadPlacement} move, package name unchanged, to the artifact
 * {@code quant-finance-library-lowlatency}. {@code Immutable} and
 * {@code ValueArrays} are used by stable packages ({@code core}, {@code rates},
 * {@code risk} and others), so they stay in the main artifact but leave the
 * public API (planned: a non-exported {@code com.quantfinlib.internal}
 * package). See docs/MIGRATION_3.0.md.</p>
 */
package com.quantfinlib.util;
