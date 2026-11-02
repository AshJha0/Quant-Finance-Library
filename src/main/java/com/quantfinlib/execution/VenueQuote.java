package com.quantfinlib.execution;

/**
 * A venue's dealable top of book for routing. For dark venues, bid/ask sizes
 * are the estimated executable liquidity and fills are assumed at the
 * venue's midpoint.
 *
 * @param lastUpdateNanos the feed timestamp (same clock as the {@code
 *                         nowNanos} passed to {@link SmartOrderRouter#route})
 *                         this quote was last refreshed at, so a router can
 *                         detect a dead/frozen feed. The deprecated 8-arg constructor
 *                         defaults this to {@link Long#MAX_VALUE} ("always
 *                         fresh") for callers that don't track quote age.
 */
public record VenueQuote(String venue, double bid, long bidSize, double ask, long askSize,
                         double feeBps, long latencyNanos, boolean dark, long lastUpdateNanos) {

    /**
     * Back-compat constructor: quote age unknown, treated as never stale.
     *
     * @deprecated since 2.4.0, removed in 3.0: a quote that can never go
     *     stale hides a dead feed from {@link SmartOrderRouter}. Use the
     *     canonical constructor and pass the feed timestamp as
     *     {@code lastUpdateNanos}; pass {@link Long#MAX_VALUE} only if you
     *     really mean "never stale" (that is exactly what this constructor does).
     */
    @Deprecated(since = "2.4.0", forRemoval = true)
    public VenueQuote(String venue, double bid, long bidSize, double ask, long askSize,
                       double feeBps, long latencyNanos, boolean dark) {
        this(venue, bid, bidSize, ask, askSize, feeBps, latencyNanos, dark, Long.MAX_VALUE);
    }

    public double mid() {
        return (bid + ask) / 2;
    }
}
