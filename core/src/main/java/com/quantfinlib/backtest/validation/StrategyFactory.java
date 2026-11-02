package com.quantfinlib.backtest.validation;

import com.quantfinlib.backtest.TradingStrategy;

import java.util.Map;

/** Builds a strategy instance from one parameter combination. */
@FunctionalInterface
public interface StrategyFactory {

    TradingStrategy create(Map<String, Double> params);
}
