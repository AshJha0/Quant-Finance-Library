package com.quantfinlib.backtest;

/**
 * A completed round-trip trade. {@code pnl} is net of commissions;
 * {@code returnPct} is relative to capital committed at entry.
 */
public record Trade(
        String symbol,
        int entryIndex,
        int exitIndex,
        long entryTime,
        long exitTime,
        double entryPrice,
        double exitPrice,
        double quantity,
        double pnl,
        double returnPct,
        String exitReason) {

    public static final String REASON_SIGNAL = "SIGNAL";
    public static final String REASON_STOP_LOSS = "STOP_LOSS";
    public static final String REASON_TAKE_PROFIT = "TAKE_PROFIT";
    public static final String REASON_END_OF_DATA = "END_OF_DATA";

    public boolean isWin() {
        return pnl > 0;
    }

    public int barsHeld() {
        return exitIndex - entryIndex;
    }
}
