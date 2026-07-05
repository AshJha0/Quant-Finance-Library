package com.quantfinlib.trading;

import com.quantfinlib.marketdata.TickListener;
import com.quantfinlib.orderbook.Side;

/**
 * Live position-band auto-hedger on the fast lane — the streaming
 * counterpart to the batch {@code hedging.DeltaHedger}: while the batch
 * hedger rebalances on a schedule against a model delta, this one watches
 * the risk gate's live position on every tick and fires a flattening order
 * through the {@link HftOrderGateway} the moment the band is breached.
 *
 * <pre>
 *   |position| > positionBand  →  submit opposite-side order for the excess
 *                                  over the band (hedge back TO the band)
 * </pre>
 *
 * <p>Hedging to the band edge rather than to flat is deliberate: it removes
 * the limit breach with the smallest order, avoids ping-ponging around zero,
 * and matches how dealer books actually run inventory. A cooldown
 * ({@code minHedgeIntervalNanos}) stops the hedger from stacking orders
 * while the first hedge's fill is still in flight — positions only update
 * when fills confirm via {@link HftRiskGate#onFill}.</p>
 *
 * <p>Register as a {@link TickListener} on the bus (consumer thread), after
 * the strategy/quoter listeners so it sees the same tick they acted on.
 * Zero allocation per tick. For options books, drive the position input
 * from {@code pricing.IncrementalGreeks} delta instead of raw inventory —
 * the band logic is identical.</p>
 */
public final class AutoHedger implements TickListener {

    private final HftOrderGateway gateway;
    private final HftRiskGate riskGate;
    private final long positionBand;
    private final long minHedgeIntervalNanos;

    /** Sentinel: no hedge sent yet — the cooldown must not apply. */
    private static final long NEVER = Long.MIN_VALUE;

    // Per-symbol cooldown clock, dense-id indexed; NEVER until the first hedge.
    private final long[] lastHedgeNanos;

    // Diagnostics.
    private long hedgesSubmitted;
    private long hedgesRejected;

    /**
     * @param positionBand          absolute position tolerated without hedging (> 0)
     * @param minHedgeIntervalNanos cooldown between hedges per symbol (>= 0)
     */
    public AutoHedger(HftOrderGateway gateway, int maxSymbols, long positionBand,
                      long minHedgeIntervalNanos) {
        if (positionBand <= 0 || minHedgeIntervalNanos < 0) {
            throw new IllegalArgumentException(
                    "positionBand must be > 0 and cooldown >= 0");
        }
        this.gateway = gateway;
        this.riskGate = gateway.riskGate();
        this.positionBand = positionBand;
        this.minHedgeIntervalNanos = minHedgeIntervalNanos;
        this.lastHedgeNanos = new long[maxSymbols];
        // Zero would silently suppress startup hedges on clocks that begin
        // near 0 (replays) or negative (System.nanoTime's arbitrary origin).
        java.util.Arrays.fill(lastHedgeNanos, NEVER);
    }

    /** The hot path: band check per tick; an order only on breach. */
    @Override
    public void onTick(int symbolId, double price, double size, long timestampNanos) {
        long position = riskGate.position(symbolId);
        long excess = Math.abs(position) - positionBand;
        if (excess <= 0) {
            return; // inside the band: nothing to do (the overwhelmingly common case)
        }
        // Sentinel checked separately: subtracting NEVER would overflow.
        if (lastHedgeNanos[symbolId] != NEVER
                && timestampNanos - lastHedgeNanos[symbolId] < minHedgeIntervalNanos) {
            return; // a hedge is already working; wait for its fill
        }
        // Long inventory sells the excess, short buys it back.
        Side side = position > 0 ? Side.SELL : Side.BUY;
        if (gateway.submit(symbolId, side, excess, price, timestampNanos) > 0) {
            hedgesSubmitted++;
            lastHedgeNanos[symbolId] = timestampNanos;
        } else {
            hedgesRejected++;
        }
    }

    /** Hedge orders accepted onto the wire. */
    public long hedgesSubmitted() {
        return hedgesSubmitted;
    }

    /** Hedge attempts refused (risk gate or full ring) — monitor this. */
    public long hedgesRejected() {
        return hedgesRejected;
    }
}
