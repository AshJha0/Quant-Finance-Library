package com.quantfinlib.microstructure;

import com.quantfinlib.orderbook.Side;

/** A matched trade (fill) for TCA and venue analytics. */
public record Execution(String symbol, Side side, double price, long quantity,
                        long timestampNanos, String venue) {

    public double notional() {
        return price * quantity;
    }
}
