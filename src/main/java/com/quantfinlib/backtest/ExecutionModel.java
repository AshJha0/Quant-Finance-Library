package com.quantfinlib.backtest;

import com.quantfinlib.core.BarSeries;
import com.quantfinlib.microstructure.Execution;
import com.quantfinlib.orderbook.Side;

import java.util.List;

/**
 * How parent orders turn into fills in an execution-aware backtest
 * ({@link ExecutionAwareBacktester}). The engine calls {@link #execute} once
 * per bar while a parent order is working; anything not filled carries over
 * to the next bar.
 *
 * <p>Returned fill prices are <b>all-in</b> (fees and spread folded in), so
 * the engine's cash accounting is simply price × quantity.</p>
 */
public interface ExecutionModel {

    /**
     * Notification that a new parent order has been created (entry or exit).
     * Stateful models (e.g. {@link IcebergExecution}) reset per-parent state here.
     */
    default void onParentOrder(Side side, long totalQuantity, int signalIndex) {
    }

    /**
     * Executes up to {@code requestedQty} on this bar. Must never fill more
     * than requested; may fill less (or nothing) — the remainder is retried
     * on subsequent bars.
     */
    List<Execution> execute(Side side, long requestedQty, BarSeries series, int index);
}
