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

    /**
     * Price-pair initialisation used by the survivorship-aware engine:
     * {@code signalData} is split and dividend adjusted and is what signals
     * must be computed on; {@code tradeData} is the unadjusted series the
     * engine trades on, available for strategies that need actual prices
     * (share-count limits, price filters). The default ignores
     * {@code tradeData}.
     */
    default void init(Map<String, BarSeries> signalData, Map<String, BarSeries> tradeData) {
        init(signalData);
    }

    /**
     * Target weights by symbol decided at bar {@code index}'s close; when
     * they fill is the engine's {@code fillPolicy} (next open by default).
     */
    Map<String, Double> targetWeights(int index);
}
