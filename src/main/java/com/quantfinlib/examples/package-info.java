/**
 * Runnable demonstrations and benchmarks (excluded from coverage — they are
 * run manually or by the {@code Benchmarks (Linux)} workflow):
 * {@link com.quantfinlib.examples.QuickStartDemo} tours every capability on
 * synthetic data;
 * {@link com.quantfinlib.examples.HftLatencyBenchmark} measures the market
 * data hot path and {@link com.quantfinlib.examples.HftOrderBenchmark} the
 * order path (both print a {@code HiccupMonitor} summary to attribute tail
 * outliers to platform stalls). See {@code docs/ULTRA_LOW_LATENCY.md} for
 * how to run them meaningfully.
 */
package com.quantfinlib.examples;
