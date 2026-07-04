package com.quantfinlib.backtest.portfolio;

import com.quantfinlib.core.BarSeries;

import java.util.Map;

/**
 * A multi-asset, weight-based strategy for the {@link PortfolioBacktester}.
 * All series must be index-aligned (same length, same bar times). Weights are
 * fractions of current equity: positive = long, negative = short, missing
 * symbol = flat; |weights| may sum above 1 for leverage.
 */
public interface PortfolioStrategy {

    String name();

    void init(Map<String, BarSeries> data);

    /** Target weights by symbol as of bar {@code index} (decided at that bar's close). */
    Map<String, Double> targetWeights(int index);
}
