package com.quantfinlib.trading;

import com.quantfinlib.orderbook.Side;

/**
 * Post-trade callback: one call per execution the {@link HftOrderGateway}
 * accepted, after the order state and the risk gate have been updated. A
 * {@link PositionLedger} is attached with {@link LedgerFillListener}.
 *
 * <p>Called on whichever thread delivered the venue report (normally the
 * venue adapter's thread), in report order. A rejected report (unknown
 * order, illegal transition, overfill) is not published. An exception from a
 * listener is counted ({@link HftOrderGateway#fillListenerErrorCount}) and
 * does not stop later listeners.</p>
 *
 * @since 2.2.0
 */
@FunctionalInterface
public interface FillListener {

    /**
     * @param execId         the venue's execution id, or a gateway sequence
     *                       number when the venue supplied none
     * @param orderId        gateway order id
     * @param symbolId       dense symbol id
     * @param side           side of the order
     * @param quantity       quantity of this execution (a partial fill is one call)
     * @param price          execution price
     * @param timestampNanos venue report time
     */
    void onFill(long execId, long orderId, int symbolId, Side side, long quantity, double price,
                long timestampNanos);
}
