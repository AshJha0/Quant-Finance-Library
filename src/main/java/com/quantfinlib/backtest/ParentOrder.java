package com.quantfinlib.backtest;

import com.quantfinlib.microstructure.Execution;
import com.quantfinlib.orderbook.Side;

import java.util.List;

/**
 * One parent order worked by the {@link ExecutionAwareBacktester}: the signal
 * that created it, the arrival price (close at signal time — the TCA
 * benchmark), and the child fills with the bar index each filled on.
 * {@code reason} is {@code "ENTRY"} for entries, or the exit reason
 * (SIGNAL / STOP_LOSS / TAKE_PROFIT / END_OF_DATA).
 */
public record ParentOrder(Side side, int signalIndex, double arrivalPrice, String reason,
                          List<Execution> fills, int[] fillBarIndices) {

    public static final String REASON_ENTRY = "ENTRY";

    public long filledQty() {
        long qty = 0;
        for (Execution f : fills) {
            qty += f.quantity();
        }
        return qty;
    }

    public double avgFillPrice() {
        long qty = 0;
        double notional = 0;
        for (Execution f : fills) {
            qty += f.quantity();
            notional += f.notional();
        }
        return qty == 0 ? Double.NaN : notional / qty;
    }

    /** Bars from first to last fill (0 for unfilled or single-bar parents). */
    public int fillDurationBars() {
        return fills.isEmpty() ? 0 : fillBarIndices[fillBarIndices.length - 1] - fillBarIndices[0];
    }
}
