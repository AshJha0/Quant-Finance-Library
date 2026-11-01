package com.quantfinlib.backtest;

import com.quantfinlib.core.BarSeries;

/**
 * A bar-driven trading strategy. {@link #init(BarSeries)} is called once by the
 * backtester (or live engine) to precompute indicators; {@link #onBar(int)} is
 * then invoked for each bar in order and must be allocation-free for
 * low-latency execution.
 */
public interface TradingStrategy {

    String name();

    void init(BarSeries series);

    Signal onBar(int index);

    /**
     * Position-aware variant, called by {@link Backtester} with the quantity
     * currently held (0 when flat, positive when long). Strategies whose
     * entry and exit conditions can both hold on the same bar should
     * override it and evaluate the exit first while long; otherwise a
     * level-type entry rule masks every exit. The default ignores the
     * position.
     */
    default Signal onBar(int index, double position) {
        return onBar(index);
    }

    /** Optional per-trade stop loss as a fraction (0 = disabled). */
    default double stopLossPct() {
        return 0;
    }

    /** Optional per-trade take profit as a fraction (0 = disabled). */
    default double takeProfitPct() {
        return 0;
    }
}
