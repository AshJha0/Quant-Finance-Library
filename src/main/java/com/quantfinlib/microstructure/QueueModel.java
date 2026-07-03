package com.quantfinlib.microstructure;

/**
 * Queue positioning and priority analytics: how position in the price-time
 * queue — and small latency differences in reaching it — translate into fill
 * probability.
 *
 * <p>Model: executed volume at a price level over a horizon is treated as
 * exponentially distributed with mean {@code expectedTradedQty}; an order
 * fills when cumulative executions reach the quantity ahead of it plus its
 * own size, giving {@code P(fill) = exp(-(qtyAhead + orderQty) / expectedTradedQty)}.
 * A deliberately simple, closed-form model — calibrate {@code expectedTradedQty}
 * from observed level turnover.</p>
 */
public final class QueueModel {

    private QueueModel() {
    }

    /**
     * Probability the order fully fills within the horizon.
     *
     * @param qtyAhead         resting quantity ahead in the queue (see
     *                         {@code OrderBook.qtyAhead})
     * @param orderQty         our order size
     * @param expectedTradedQty expected volume to execute at this level over the horizon
     */
    public static double fillProbability(long qtyAhead, long orderQty, double expectedTradedQty) {
        if (expectedTradedQty <= 0) {
            return 0;
        }
        return Math.exp(-(qtyAhead + orderQty) / expectedTradedQty);
    }

    /**
     * Extra quantity that joins the queue ahead of an order arriving
     * {@code latencyNanos} later, given the rate at which others join.
     */
    public static double queueGrowth(double joinRateQtyPerSec, long latencyNanos) {
        return Math.max(0, joinRateQtyPerSec) * latencyNanos / 1e9;
    }

    /**
     * Fill-probability edge from being {@code latencyAdvantageNanos} faster to
     * the queue: P(fill | fast arrival) - P(fill | slow arrival).
     */
    public static double latencyFillAdvantage(long qtyAhead, long orderQty, double expectedTradedQty,
                                              double joinRateQtyPerSec, long latencyAdvantageNanos) {
        double fast = fillProbability(qtyAhead, orderQty, expectedTradedQty);
        long extra = Math.round(queueGrowth(joinRateQtyPerSec, latencyAdvantageNanos));
        double slow = fillProbability(qtyAhead + extra, orderQty, expectedTradedQty);
        return fast - slow;
    }
}
