package com.quantfinlib.regulatory;

import com.quantfinlib.orderbook.Side;

/**
 * Market quality indices used in execution-quality and venue-quality
 * reporting: quoted / effective / realized spread, price impact, and
 * order-to-trade ratio. All spreads in basis points; signs follow the
 * convention that positive = cost to the liquidity taker.
 */
public final class MarketQualityMetrics {

    private MarketQualityMetrics() {
    }

    public static double quotedSpreadBps(double bid, double ask) {
        double mid = (bid + ask) / 2;
        return mid == 0 ? Double.NaN : (ask - bid) / mid * 1e4;
    }

    /** Effective spread: {@code 2 * sign * (price - mid) / mid} — what the taker actually paid. */
    public static double effectiveSpreadBps(Side takerSide, double price, double midAtExecution) {
        return 2.0 * takerSide.sign() * (price - midAtExecution) / midAtExecution * 1e4;
    }

    /**
     * Realized spread: effective spread measured against the mid some horizon
     * after the trade — the part of the spread the liquidity provider kept
     * after adverse selection.
     */
    public static double realizedSpreadBps(Side takerSide, double price, double midAfterHorizon) {
        return 2.0 * takerSide.sign() * (price - midAfterHorizon) / midAfterHorizon * 1e4;
    }

    /**
     * Price impact: how far the mid moved in the taker's direction after the
     * trade ({@code 2 * sign * (midAfter - midAtExec) / midAtExec}); effective
     * spread ≈ realized spread + price impact.
     */
    public static double priceImpactBps(Side takerSide, double midAtExecution, double midAfterHorizon) {
        return 2.0 * takerSide.sign() * (midAfterHorizon - midAtExecution) / midAtExecution * 1e4;
    }

    /** Messages (orders + cancels + replaces) per executed trade. */
    public static double orderToTradeRatio(long messages, long trades) {
        return trades == 0 ? Double.POSITIVE_INFINITY : (double) messages / trades;
    }
}
