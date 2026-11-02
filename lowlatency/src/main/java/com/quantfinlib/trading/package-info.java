/**
 * Order entry, pre-trade risk and quoting: a zero-allocation lane for the
 * hot path and a paper-trading lane for simulation, mirroring
 * {@code marketdata}.
 *
 * <p><b>Fast lane</b>: {@link com.quantfinlib.trading.HftRiskGate}
 * (zero-allocation pre-trade checks over int ids: price validity, size,
 * notional, daily caps, position including open orders, collar, duplicate
 * orders; limits adjustable from any thread),
 * {@link com.quantfinlib.trading.OrderRingBuffer} (SPSC primitive ring
 * carrying new, cancel, replace and cancel-all messages) and
 * {@link com.quantfinlib.trading.HftOrderGateway} (submit, cancel, replace,
 * cancel-all; per-order lifecycle in an
 * {@link com.quantfinlib.trading.OrderStateTable} following
 * {@link com.quantfinlib.trading.OrderStateMachine}; a kill switch that
 * sends a cancel-all; contained venue-listener failures). On top of the
 * lane: {@link com.quantfinlib.trading.HftQuoter} (two-sided quoting that
 * keeps one working order per side by cancel/replace),
 * {@link com.quantfinlib.trading.AvellanedaStoikov} (closed-form
 * reservation price and optimal spread, Avellaneda and Stoikov 2008),
 * {@link com.quantfinlib.trading.AutoHedger} (position-band hedging with
 * one working IOC per symbol), {@link com.quantfinlib.trading.OrderThrottle}
 * (venue message-rate token bucket, wired into the gateway) and
 * {@link com.quantfinlib.trading.LastLookGate} (maker-side symmetric
 * last-look price check per the FX Global Code). Venue adapters implement
 * {@link com.quantfinlib.trading.OrderListener} and report back through the
 * gateway; {@link com.quantfinlib.trading.LoopbackVenue} is an in-process
 * venue for tests and benchmarks. Scale-out:
 * {@link com.quantfinlib.trading.ShardedTradingEngine} (shared-nothing
 * shards) under {@link com.quantfinlib.trading.GlobalRiskAggregator}
 * (firm-wide worst-case gross-notional circuit breaker that fails closed on
 * unpriced exposure).</p>
 *
 * <p><b>Simulation lane</b>:
 * {@link com.quantfinlib.trading.PaperTradingGateway} (quote-driven fills
 * limited by displayed size, average-cost accounting, pre-trade risk check,
 * the same order state machine) observed through
 * {@link com.quantfinlib.trading.TradingDashboard} (embedded HTTP, bound to
 * loopback by default).</p>
 *
 * <p><b>Post-trade</b>: {@link com.quantfinlib.trading.HftOrderGateway#addFillListener}
 * publishes every accepted fill, with the venue execution id, to
 * {@link com.quantfinlib.trading.FillListener}s;
 * {@link com.quantfinlib.trading.LedgerFillListener} books them into a
 * {@link com.quantfinlib.trading.PositionLedger}, which
 * {@link com.quantfinlib.trading.DropCopyReconciler} checks against a drop
 * copy.</p>
 *
 * <p>Since 3.0 this package is in the artifact
 * {@code quant-finance-library-lowlatency} (module {@code com.quantfinlib.lowlatency});
 * the package name did not change, so a 2.x user changes the dependency, not the
 * imports. See docs/MIGRATION_3.0.md.</p>
 */
package com.quantfinlib.trading;
