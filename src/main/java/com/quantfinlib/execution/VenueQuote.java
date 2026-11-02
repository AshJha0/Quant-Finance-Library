package com.quantfinlib.execution;

/**
 * A venue's dealable top of book for routing. For dark venues, bid/ask sizes
 * are the estimated executable liquidity and fills are assumed at the
 * venue's midpoint.
 *
 * @param lastUpdateNanos the feed timestamp (same clock as the {@code
 *                         nowNanos} passed to {@link SmartOrderRouter#route})
 *                         this quote was last refreshed at, so a router can
 *                         detect a dead/frozen feed. Pass {@link Long#MAX_VALUE}
 *                         only to mean "always
 *                         fresh" (the 2.x 8-argument constructor did this).
 */
public record VenueQuote(String venue, double bid, long bidSize, double ask, long askSize,
                         double feeBps, long latencyNanos, boolean dark, long lastUpdateNanos) {

    public double mid() {
        return (bid + ask) / 2;
    }
}
