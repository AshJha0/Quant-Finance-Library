package com.quantfinlib.backtest;

import com.quantfinlib.core.BarSeries;
import com.quantfinlib.execution.IcebergOrder;
import com.quantfinlib.microstructure.Execution;
import com.quantfinlib.orderbook.Side;

import java.util.List;

/**
 * Iceberg execution: wraps another {@link ExecutionModel} and caps each bar's
 * execution at the {@link IcebergOrder} state machine's visible tranche
 * (optionally randomized), plus an optional participation cap versus the
 * bar's volume. A fresh iceberg is loaded for every parent order, so entries
 * and exits are both worked patiently across bars.
 */
public final class IcebergExecution implements ExecutionModel {

    private final ExecutionModel inner;
    private final long displayQty;
    private final double randomizePct;
    private final double maxParticipation;   // 0 = disabled
    private final long seed;
    private IcebergOrder iceberg;
    private long parentSeq;

    public IcebergExecution(ExecutionModel inner, long displayQty) {
        this(inner, displayQty, 0, 0, 0);
    }

    /**
     * @param randomizePct     display-size jitter, e.g. 0.2 = ±20%
     * @param maxParticipation cap per bar as a fraction of bar volume (0 = off)
     */
    public IcebergExecution(ExecutionModel inner, long displayQty, double randomizePct,
                            double maxParticipation, long seed) {
        if (displayQty <= 0) {
            throw new IllegalArgumentException("displayQty must be positive");
        }
        this.inner = inner;
        this.displayQty = displayQty;
        this.randomizePct = randomizePct;
        this.maxParticipation = maxParticipation;
        this.seed = seed;
    }

    @Override
    public void onParentOrder(Side side, long totalQuantity, int signalIndex) {
        iceberg = new IcebergOrder(totalQuantity, displayQty, randomizePct,
                seed + parentSeq++ * 1_000_003L);
        inner.onParentOrder(side, totalQuantity, signalIndex);
    }

    @Override
    public List<Execution> execute(Side side, long requestedQty, BarSeries series, int index) {
        if (iceberg == null || iceberg.isComplete() || requestedQty <= 0) {
            return List.of();
        }
        long cap = Math.min(requestedQty, iceberg.visibleQty());
        if (maxParticipation > 0) {
            cap = Math.min(cap, (long) (series.volume(index) * maxParticipation));
        }
        if (cap <= 0) {
            return List.of();
        }
        List<Execution> fills = inner.execute(side, cap, series, index);
        long filled = 0;
        for (Execution f : fills) {
            filled += f.quantity();
        }
        if (filled > 0) {
            iceberg.onFill(filled);
        }
        return fills;
    }
}
