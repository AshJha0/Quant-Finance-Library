package com.quantfinlib.trading;

import com.quantfinlib.orderbook.Side;

/**
 * All-primitive order callback on the venue side of the fast lane: no order
 * object, no boxing, no allocation. Invoked on the gateway's venue thread.
 */
@FunctionalInterface
public interface OrderListener {

    void onOrder(long orderId, int symbolId, Side side, long quantity, double price,
                 long timestampNanos);
}
