package com.quantfinlib.backtest.tick;

import com.quantfinlib.orderbook.Side;

/**
 * Order entry and account access for a {@link TickStrategy}. Orders placed
 * during {@code onTick} are only eligible to fill from the <i>next</i> tick
 * onwards — you cannot trade against the print you are reacting to.
 */
public interface TickTradingContext {

    /**
     * Places a passive limit order. It fills when a later tick trades through
     * the price, or partially as traded volume at the price works off the
     * simulated queue ahead of it.
     *
     * @return order id for {@link #cancel}
     */
    long submitLimit(int symbolId, Side side, double price, long quantity);

    /**
     * Immediate execution at the last trade price plus half the configured
     * spread (aggressor pays the spread). Returns a negative id if no price
     * has been seen for the symbol yet.
     */
    long submitMarket(int symbolId, Side side, long quantity);

    /** Cancels a working limit order. False if unknown or already filled. */
    boolean cancel(long orderId);

    long position(int symbolId);

    double cash();

    /** Last traded price for the symbol (NaN before its first tick). */
    double lastPrice(int symbolId);

    /** Symbol name for an id from the tick file. */
    String symbolName(int symbolId);
}
