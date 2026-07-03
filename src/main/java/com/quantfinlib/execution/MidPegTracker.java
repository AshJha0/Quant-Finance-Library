package com.quantfinlib.execution;

import com.quantfinlib.orderbook.Side;

/**
 * Mid-rate pegging model: tracks the target price of a mid-pegged order with
 * an offset and optional limit cap, and decides when the peg has drifted far
 * enough to justify a reprice (each reprice costs queue priority and a
 * message, so small moves are ignored).
 */
public final class MidPegTracker {

    private final Side side;
    private final double offset;            // signed, added to the mid
    private final double limitPrice;        // NaN = uncapped
    private final double repriceThreshold;  // minimum peg move to reprice
    private double currentPrice = Double.NaN;

    /**
     * @param offset           signed offset from the mid (negative = more passive for a buy)
     * @param limitPrice       hard cap: never price through this (NaN to disable)
     * @param repriceThreshold minimum absolute peg move before repricing
     */
    public MidPegTracker(Side side, double offset, double limitPrice, double repriceThreshold) {
        this.side = side;
        this.offset = offset;
        this.limitPrice = limitPrice;
        this.repriceThreshold = repriceThreshold;
    }

    /**
     * Feed a top-of-book update. Returns the new order price when a reprice is
     * warranted, or NaN when the current price should be left alone.
     */
    public double onQuote(double bid, double ask) {
        double target = (bid + ask) / 2 + offset;
        if (!Double.isNaN(limitPrice)) {
            target = side == Side.BUY ? Math.min(target, limitPrice) : Math.max(target, limitPrice);
        }
        if (Double.isNaN(currentPrice) || Math.abs(target - currentPrice) >= repriceThreshold) {
            currentPrice = target;
            return target;
        }
        return Double.NaN;
    }

    /** Current working price (NaN before the first quote). */
    public double currentPrice() {
        return currentPrice;
    }
}
