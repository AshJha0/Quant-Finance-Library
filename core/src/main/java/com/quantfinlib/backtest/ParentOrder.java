package com.quantfinlib.backtest;

import com.quantfinlib.microstructure.Execution;
import com.quantfinlib.core.Side;

import java.util.List;

/**
 * One parent order worked by the {@link ExecutionAwareBacktester}: the signal
 * that created it, the arrival price (close at signal time — the TCA
 * benchmark), and the child fills with the bar index each filled on.
 * {@code reason} is {@code "ENTRY"} for entries, or the exit reason
 * (signal / STOP_LOSS / TAKE_PROFIT / END_OF_DATA).
 */
public final class ParentOrder {
    private final Side side;
    private final int signalIndex;
    private final double arrivalPrice;
    private final String reason;
    private final List<Execution> fills;
    private final int[] fillBarIndices;

    /**
     * Creates the value; array and collection arguments are copied.
     */
    public ParentOrder(Side side, int signalIndex, double arrivalPrice, String reason, List<Execution> fills,
            int[] fillBarIndices) {
        this.side = side;
        this.signalIndex = signalIndex;
        this.arrivalPrice = arrivalPrice;
        this.reason = reason;
        this.fills = List.copyOf(fills);
        this.fillBarIndices = fillBarIndices == null ? null : fillBarIndices.clone();
    }

    /** {@code side}. */
    public Side side() {
        return side;
    }

    /** {@code signalIndex}. */
    public int signalIndex() {
        return signalIndex;
    }

    /** {@code arrivalPrice}. */
    public double arrivalPrice() {
        return arrivalPrice;
    }

    /** {@code reason}. */
    public String reason() {
        return reason;
    }

    /** {@code fills}. */
    public List<Execution> fills() {
        return fills;
    }

    /** Element {@code i} of {@code fillBarIndices}. */
    public int fillBarIndices(int i) {
        return fillBarIndices[i];
    }

    /** Length of {@code fillBarIndices}. */
    public int fillBarIndicesLength() {
        return fillBarIndices.length;
    }

    /** A fresh copy of {@code fillBarIndices}. */
    public int[] fillBarIndicesCopy() {
        return fillBarIndices == null ? null : fillBarIndices.clone();
    }

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

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ParentOrder o)) {
            return false;
        }
        return java.util.Objects.equals(side, o.side)
                && signalIndex == o.signalIndex
                && Double.compare(arrivalPrice, o.arrivalPrice) == 0
                && java.util.Objects.equals(reason, o.reason)
                && java.util.Objects.equals(fills, o.fills)
                && java.util.Arrays.equals(fillBarIndices, o.fillBarIndices);
    }

    @Override
    public int hashCode() {
        int h = 0;
        h = 31 * h + java.util.Objects.hashCode(side);
        h = 31 * h + Integer.hashCode(signalIndex);
        h = 31 * h + Double.hashCode(arrivalPrice);
        h = 31 * h + java.util.Objects.hashCode(reason);
        h = 31 * h + java.util.Objects.hashCode(fills);
        h = 31 * h + java.util.Arrays.hashCode(fillBarIndices);
        return h;
    }

    @Override
    public String toString() {
        return "ParentOrder[" + "side=" + side + ", " + "signalIndex=" + signalIndex + ", " + "arrivalPrice=" + arrivalPrice + ", " + "reason=" + reason + ", " + "fills=" + fills + ", " + "fillBarIndices=" + java.util.Arrays.toString(fillBarIndices) + "]";
    }
}
