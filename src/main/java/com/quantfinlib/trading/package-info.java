/**
 * Order entry, two lanes (mirroring {@code marketdata}):
 *
 * <p><b>HFT fast lane</b> — {@link com.quantfinlib.trading.HftRiskGate}
 * (zero-allocation pre-trade checks over int ids, ≈3 ns, cross-thread
 * safe via VarHandle acquire/release),
 * {@link com.quantfinlib.trading.OrderRingBuffer} (SPSC primitive ring) and
 * {@link com.quantfinlib.trading.HftOrderGateway} (submit = risk check +
 * release-store publish; measured tick-to-order p50 ≈ 504 ns). On top of
 * the lane: {@link com.quantfinlib.trading.HftQuoter} (streaming two-sided
 * market maker with inventory skew, tick-grid snap and conflation —
 * measured tick-to-two-sided-quote p50 ≈ 592 ns) with
 * {@link com.quantfinlib.trading.AvellanedaStoikov} (the principled
 * quoting model behind the quoter's heuristic skew: closed-form
 * reservation price and optimal spread from risk aversion, variance and
 * fill intensity), {@link com.quantfinlib.trading.AutoHedger} (live
 * position-band hedging with in-flight cooldown),
 * {@link com.quantfinlib.trading.OrderThrottle}
 * (venue message-rate token bucket) and
 * {@link com.quantfinlib.trading.LastLookGate} (maker-side symmetric
 * last-look price check per the FX Global Code). Venue adapters implement
 * {@link com.quantfinlib.trading.OrderListener} — see
 * {@code sbe.BinaryOrderPublisher} and the FIX engine. Scale-out:
 * {@link com.quantfinlib.trading.ShardedTradingEngine} (shared-nothing
 * shards) under {@link com.quantfinlib.trading.GlobalRiskAggregator}
 * (firm-wide gross-notional circuit breaker).</p>
 *
 * <p><b>Simulation lane</b> —
 * {@link com.quantfinlib.trading.PaperTradingGateway} (quote-driven fills,
 * average-cost accounting, pre-trade risk gate; fully synchronized with
 * atomic {@code snapshot()}) observed live through
 * {@link com.quantfinlib.trading.TradingDashboard} (zero-dep embedded HTTP).</p>
 */
package com.quantfinlib.trading;
