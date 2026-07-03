package com.quantfinlib.orderbook;

/** Order side. */
public enum Side {
    BUY, SELL;

    /** +1 for BUY, -1 for SELL — for signed cost/slippage arithmetic. */
    public int sign() {
        return this == BUY ? 1 : -1;
    }

    public Side opposite() {
        return this == BUY ? SELL : BUY;
    }
}
