package com.quantfinlib.trading;

import com.quantfinlib.orderbook.Side;

/**
 * Order entry abstraction — the seam between strategy code and the venue.
 * Strategies talk to this interface only, so the same strategy runs against
 * {@link PaperTradingGateway} in simulation and a broker adapter in
 * production.
 */
public interface OrderGateway {

    @FunctionalInterface
    interface ExecutionListener {
        void onFill(long orderId, String symbol, Side side, double price, long quantity,
                    long timestampNanos);
    }

    /** Submits a limit order; returns the order id (status may be REJECTED). */
    long submitLimit(String symbol, Side side, long quantity, double price);

    /** Submits a market order for immediate execution at the touch. */
    long submitMarket(String symbol, Side side, long quantity);

    /** Cancels a working order. Returns false if unknown or already terminal. */
    boolean cancel(long orderId);

    OrderStatus status(long orderId);

    void addExecutionListener(ExecutionListener listener);
}
