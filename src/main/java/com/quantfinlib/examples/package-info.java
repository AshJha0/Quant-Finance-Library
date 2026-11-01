/**
 * Runnable demonstrations and benchmarks, shipped in the separate
 * {@code examples} jar and excluded from coverage. They are run by hand or
 * by the {@code Benchmarks (Linux)} workflow:
 * {@link com.quantfinlib.examples.QuickStartDemo} tours every capability on
 * synthetic data;
 * {@link com.quantfinlib.examples.HftLatencyBenchmark} measures the market
 * data hot path and {@link com.quantfinlib.examples.HftOrderBenchmark} the
 * order path (both print a {@code HiccupMonitor} summary to attribute tail
 * outliers to platform stalls). See {@code docs/ULTRA_LOW_LATENCY.md} for
 * how to run them meaningfully.
 *
 * <p>Run the benchmarks with a fixed heap and the recommended JDK 25 flags,
 * as the workflow does:
 * <pre>
 * java -Xms1g -Xmx1g -XX:+UseZGC -XX:+UseCompactObjectHeaders -XX:+AlwaysPreTouch \
 *      -XX:+PerfDisableSharedMem -cp target/classes com.quantfinlib.examples.HftLatencyBenchmark
 * </pre>
 * For a bounded run that must not allocate, swap ZGC for
 * {@code -XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC}: a run that
 * allocates past the heap fails instead of pausing.
 */
package com.quantfinlib.examples;
