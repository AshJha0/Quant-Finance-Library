package com.quantfinlib.trading;

import com.quantfinlib.core.Side;

/**
 * All-primitive order-entry callback on the venue side of the fast lane: no
 * order object, no boxing, no allocation. Invoked on the gateway's venue
 * thread, in ring order.
 *
 * <p>Message types carried by the {@link OrderRingBuffer}: new order
 * ({@link #onOrder} or, with time-in-force flags, {@link #onNewOrder}),
 * cancel ({@link #onCancel}), cancel/replace ({@link #onReplace}) and mass
 * cancel ({@link #onCancelAll}). Only {@link #onOrder} is abstract, so an
 * adapter written before cancels existed still compiles; such an adapter
 * silently ignores cancels, which is unsafe on a live venue, so live
 * adapters must override the cancel methods.</p>
 *
 * <p>Venue adapters report acknowledgements, fills, rejects and cancel
 * confirmations back through {@link HftOrderGateway#onAck},
 * {@link HftOrderGateway#onFill} and the related methods, which drive the
 * order state machine and release risk-gate exposure.</p>
 */
@FunctionalInterface
public interface OrderListener {

    /** Symbol id passed to {@link #onCancelAll} meaning every symbol. */
    int ALL_SYMBOLS = -1;

    /** No flags: a day limit order. */
    int FLAG_NONE = 0;
    /** Immediate-or-cancel: any quantity not filled on arrival is cancelled. */
    int FLAG_IOC = 1;

    /** New limit order. {@code price} is a finite positive limit price. */
    void onOrder(long orderId, int symbolId, Side side, long quantity, double price,
                 long timestampNanos);

    /**
     * New order with time-in-force flags ({@link #FLAG_IOC}). The default
     * forwards to {@link #onOrder} and drops the flags, so adapters that
     * support IOC must override this method.
     */
    default void onNewOrder(long orderId, int symbolId, Side side, long quantity, double price,
                            int flags, long timestampNanos) {
        onOrder(orderId, symbolId, side, quantity, price, timestampNanos);
    }

    /** Cancel request for a previously sent order. */
    default void onCancel(long orderId, int symbolId, long timestampNanos) {
    }

    /**
     * Cancel/replace request. {@code newQuantity} is the new total order
     * quantity (FIX OrderQty(38) semantics, including anything already
     * filled), {@code newPrice} the new limit.
     */
    default void onReplace(long orderId, int symbolId, Side side, long newQuantity,
                           double newPrice, long timestampNanos) {
    }

    /**
     * Mass cancel of every working order on {@code symbolId}, or on all
     * symbols when it is {@link #ALL_SYMBOLS}. Sent by the gateway when the
     * risk gate's kill switch is engaged.
     */
    default void onCancelAll(int symbolId, long timestampNanos) {
    }
}
