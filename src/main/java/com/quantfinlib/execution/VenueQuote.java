package com.quantfinlib.execution;

/**
 * A venue's dealable top of book for routing. For dark venues, bid/ask sizes
 * are the estimated executable liquidity and fills are assumed at the
 * venue's midpoint.
 *
 * @param lastUpdateNanos the feed timestamp (same clock as the {@code
 *                         nowNanos} passed to {@link SmartOrderRouter#route})
 *                         this quote was last refreshed at, so a router can
 *                         detect a dead/frozen feed. The 8-arg constructor
 *                         defaults this to {@link Long#MAX_VALUE} ("always
 *                         fresh") for callers that don't track quote age.
 */
public record VenueQuote(String venue, double bid, long bidSize, double ask, long askSize,
                         double feeBps, long latencyNanos, boolean dark, long lastUpdateNanos) {

    /** Back-compat constructor: quote age unknown, treated as never stale. */
    public VenueQuote(String venue, double bid, long bidSize, double ask, long askSize,
                       double feeBps, long latencyNanos, boolean dark) {
        this(venue, bid, bidSize, ask, askSize, feeBps, latencyNanos, dark, Long.MAX_VALUE);
    }

    public double mid() {
        return (bid + ask) / 2;
    }
}
