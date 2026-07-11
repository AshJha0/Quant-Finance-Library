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

    /**
     * The price this model's fills are anchored to on the given bar — the
     * engine budgets entry requests as
     * {@code cash / (referencePrice * (1 + worstCaseCostFraction()))}.
     * Default: the bar close. A model that fills off a different price
     * point (e.g. {@link LastLookExecution} fills at the OPEN) must
     * override this, or a gap between close and its actual anchor lets a
     * fully-filled request overdraw cash.
     */
    default double referencePrice(BarSeries series, int index) {
        return series.close(index);
    }

    /**
     * Upper bound on this model's all-in cost as a fraction of
     * {@link #referencePrice} (spread + fees + slippage). The engine uses
     * it to size entries so that a fully-filled parent can never overdraw
     * cash — a model whose fills can cost more than
     * {@code referencePrice * (1 + worstCaseCostFraction())} MUST override
     * one or both methods, or the backtest silently trades on margin it
     * doesn't have. Wrappers must DELEGATE both to the model that actually
     * prices the fills. Default 1%.
     */
    default double worstCaseCostFraction() {
        return 0.01;
    }
}
