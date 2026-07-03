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

    /** Optional per-trade stop loss as a fraction (0 = disabled). */
    default double stopLossPct() {
        return 0;
    }

    /** Optional per-trade take profit as a fraction (0 = disabled). */
    default double takeProfitPct() {
        return 0;
    }
}
