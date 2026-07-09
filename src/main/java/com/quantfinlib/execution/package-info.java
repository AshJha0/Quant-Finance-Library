/**
 * Execution strategy support:
 * {@link com.quantfinlib.execution.SmartOrderRouter} (fee-adjusted
 * multi-venue splitting, dark-first option), its zero-allocation
 * hot-lane sibling {@link com.quantfinlib.execution.HftSor}, and the
 * full-checklist {@link com.quantfinlib.execution.AdaptiveSor}
 * (expected-cost routing over displayed + hidden liquidity, fees/rebates,
 * latency, fill probability and a reliability veto, with contingent dark
 * probes) learning from {@link com.quantfinlib.execution.VenueScorecard}
 * (streaming per-venue fill rate, measured latency, realized dark fills),
 * {@link com.quantfinlib.execution.TwapScheduler} /
 * {@link com.quantfinlib.execution.VwapScheduler} (schedule design with
 * anti-gaming jitter and exact largest-remainder allocation),
 * {@link com.quantfinlib.execution.PovTracker} (streaming
 * percentage-of-volume participation),
 * {@link com.quantfinlib.execution.ImplementationShortfallScheduler}
 * (Almgren-Chriss-optimal slicing),
 * {@link com.quantfinlib.execution.WmrFixingScheduler} (benchmark-window
 * TWAP replication),
 * {@link com.quantfinlib.execution.BenchmarkExecutor} (the DYNAMIC
 * benchmark algo: one stateful executor tracking VWAP / TWAP / Arrival /
 * Implementation Shortfall / Closing / Opening / Participation, re-deciding
 * each interval from live spread, depth, volatility, volume curve, alpha
 * and liquidity — cross-asset),
 * {@link com.quantfinlib.execution.LiquiditySeekingAlgo} (the
 * opportunistic archetype: burst when the spread is under its
 * time-of-day forecast in a calm regime, guaranteed by a completion
 * floor over the final stretch),
 * {@link com.quantfinlib.execution.PortfolioExecutor} (multi-symbol
 * portfolio-level scheduling over per-symbol BenchmarkExecutor children:
 * leg-balance band for two-sided transitions, per-interval notional budget
 * allocated risk-weighted — overlays only ever damp a child's own due, so
 * per-symbol benchmark integrity holds),
 * {@link com.quantfinlib.execution.IcebergOrder} (display/reload state
 * machine), {@link com.quantfinlib.execution.DarkPoolSimulator}
 * (midpoint cross with minimum-execution-quantity),
 * {@link com.quantfinlib.execution.MidPegTracker} (peg repricing with
 * thresholds) and {@link com.quantfinlib.execution.VenueBenchmark}
 * (fill rate, effective spread, markout per venue).
 */
package com.quantfinlib.execution;
