package com.quantfinlib.core;

/**
 * Order side. Until 2.4 this was {@code com.quantfinlib.orderbook.Side}; it moved
 * to {@code core} in 3.0 because stable packages ({@code execution},
 * {@code backtest}, {@code microstructure}, {@code regulatory}, {@code risk}) take
 * it in their signatures while {@code orderbook} moved to the low-latency module
 * (docs/MIGRATION_3.0.md, section 8). Constants and methods are unchanged.
 */
public enum Side {
    BUY, SELL;

    /** +1 for BUY, -1 for SELL — for signed cost/slippage arithmetic. */
    public int sign() {
        return this == BUY ? 1 : -1;
    }

    /** The other side: SELL for BUY, BUY for SELL. */
    public Side opposite() {
        return this == BUY ? SELL : BUY;
    }
}
