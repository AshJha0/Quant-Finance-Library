/**
 * Order entry, two lanes (mirroring {@code marketdata}):
 *
 * <p><b>HFT fast lane</b> — {@link com.quantfinlib.trading.HftRiskGate}
 * (zero-allocation pre-trade checks over int ids, ≈1 ns),
 * {@link com.quantfinlib.trading.OrderRingBuffer} (SPSC primitive ring) and
 * {@link com.quantfinlib.trading.HftOrderGateway} (submit = risk check +
 * release-store publish; measured tick-to-order p50 ≈ 504 ns). Venue
 * adapters implement {@link com.quantfinlib.trading.OrderListener} — see
 * {@code sbe.BinaryOrderPublisher} and the FIX engine.</p>
 *
 * <p><b>Simulation lane</b> —
 * {@link com.quantfinlib.trading.PaperTradingGateway} (quote-driven fills,
 * average-cost accounting, pre-trade risk gate; fully synchronized with
 * atomic {@code snapshot()}) observed live through
 * {@link com.quantfinlib.trading.TradingDashboard} (zero-dep embedded HTTP).</p>
 */
package com.quantfinlib.trading;
