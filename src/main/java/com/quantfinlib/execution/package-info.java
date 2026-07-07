/**
 * Execution strategy support:
 * {@link com.quantfinlib.execution.SmartOrderRouter} (fee-adjusted
 * multi-venue splitting, dark-first option) and its zero-allocation
 * hot-lane sibling {@link com.quantfinlib.execution.HftSor},
 * {@link com.quantfinlib.execution.TwapScheduler} /
 * {@link com.quantfinlib.execution.VwapScheduler} (schedule design with
 * anti-gaming jitter and exact largest-remainder allocation),
 * {@link com.quantfinlib.execution.PovTracker} (streaming
 * percentage-of-volume participation),
 * {@link com.quantfinlib.execution.ImplementationShortfallScheduler}
 * (Almgren-Chriss-optimal slicing),
 * {@link com.quantfinlib.execution.WmrFixingScheduler} (benchmark-window
 * TWAP replication),
 * {@link com.quantfinlib.execution.IcebergOrder} (display/reload state
 * machine), {@link com.quantfinlib.execution.DarkPoolSimulator}
 * (midpoint cross with minimum-execution-quantity),
 * {@link com.quantfinlib.execution.MidPegTracker} (peg repricing with
 * thresholds) and {@link com.quantfinlib.execution.VenueBenchmark}
 * (fill rate, effective spread, markout per venue).
 */
package com.quantfinlib.execution;
