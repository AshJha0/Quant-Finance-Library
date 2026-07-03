package com.quantfinlib.execution;

/**
 * A venue's dealable top of book for routing. For dark venues, bid/ask sizes
 * are the estimated executable liquidity and fills are assumed at the
 * venue's midpoint.
 */
public record VenueQuote(String venue, double bid, long bidSize, double ask, long askSize,
                         double feeBps, long latencyNanos, boolean dark) {

    public double mid() {
        return (bid + ask) / 2;
    }
}
