package com.quantfinlib.trading;

import com.quantfinlib.core.Side;

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

    /**
     * The venue busted (ExecType H, trade cancel) the execution
     * {@code execRefId}: it did not happen. The default does nothing, so
     * listeners written for 2.2 keep compiling and ignore busts.
     *
     * @param execId         id of the bust report itself
     * @param execRefId      id of the execution being cancelled (FIX tag 19)
     * @param quantity       quantity of the busted execution, as the caller knows it
     * @since 2.3.0
     */
    default void onBust(long execId, long execRefId, long orderId, int symbolId, Side side,
                        long quantity, long timestampNanos) {
    }

    /**
     * The venue corrected (ExecType G) the execution {@code execRefId} to
     * {@code quantity} at {@code price}. The default does nothing.
     *
     * @param execId    id of the correction report
     * @param execRefId id of the execution being corrected (FIX tag 19)
     * @param quantity  corrected quantity of that execution (replaces, not adds)
     * @param price     corrected price
     * @since 2.3.0
     */
    default void onCorrect(long execId, long execRefId, long orderId, int symbolId, Side side,
                           long quantity, double price, long timestampNanos) {
    }
}
