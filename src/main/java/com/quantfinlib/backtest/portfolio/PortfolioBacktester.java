package com.quantfinlib.backtest.portfolio;

import com.quantfinlib.backtest.PerformanceAnalytics;
import com.quantfinlib.backtest.PerformanceMetrics;
import com.quantfinlib.core.BarSeries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Multi-asset, long/short portfolio backtester: rebalances positions (possibly
 * fractional and negative) toward the strategy's target weights at a
 * configurable cadence, charging commission on traded notional. This is where
 * the {@code optimization} package meets the backtester — feed optimizer
 * weights, vol-target overlays, or momentum rankings straight in.
 */
public final class PortfolioBacktester {

    public record Config(double initialCapital, double commissionRate,
                         int rebalanceEveryBars, int periodsPerYear) {

        public static Config defaults() {
            return new Config(1_000_000, 0.001, 1, 252);
        }

        public Config withRebalanceEvery(int bars) {
            return new Config(initialCapital, commissionRate, bars, periodsPerYear);
        }
    }

    public record Result(double[] equityCurve, PerformanceMetrics metrics,
                         double totalCosts, double totalTurnoverNotional,
                         Map<String, Double> finalPositions) {
    }

    private PortfolioBacktester() {
    }

    public static Result run(PortfolioStrategy strategy, Map<String, BarSeries> data, Config config) {
        if (data.isEmpty()) {
            throw new IllegalArgumentException("no series supplied");
        }
        List<String> symbols = new ArrayList<>(data.keySet());
        int n = data.get(symbols.getFirst()).size();
        for (String s : symbols) {
            if (data.get(s).size() != n) {
                throw new IllegalArgumentException("series must be index-aligned: " + s);
            }
        }
        strategy.init(data);

        double cash = config.initialCapital();
        Map<String, Double> positions = new HashMap<>();   // signed quantities
        double[] equity = new double[n];
        double totalCosts = 0, totalTurnover = 0;

        for (int i = 0; i < n; i++) {
            double portfolioValue = cash + marketValue(positions, data, i);

            if (i % config.rebalanceEveryBars() == 0) {
                Map<String, Double> weights = strategy.targetWeights(i);
                for (String symbol : symbols) {
                    double close = data.get(symbol).close(i);
                    double targetQty = weights.getOrDefault(symbol, 0.0) * portfolioValue / close;
                    double currentQty = positions.getOrDefault(symbol, 0.0);
                    double delta = targetQty - currentQty;
                    if (delta == 0) {
                        continue;
                    }
                    double notional = Math.abs(delta) * close;
                    double fee = notional * config.commissionRate();
                    cash -= delta * close + fee;
                    totalCosts += fee;
                    totalTurnover += notional;
                    positions.put(symbol, targetQty);
                }
            }
            equity[i] = cash + marketValue(positions, data, i);
        }

        Map<String, Double> finalPositions = new LinkedHashMap<>();
        for (String symbol : symbols) {
            double qty = positions.getOrDefault(symbol, 0.0);
            if (qty != 0) {
                finalPositions.put(symbol, qty);
            }
        }
        PerformanceMetrics metrics = PerformanceAnalytics.compute(
                equity, List.of(), config.periodsPerYear());
        return new Result(equity, metrics, totalCosts, totalTurnover, finalPositions);
    }

    private static double marketValue(Map<String, Double> positions,
                                      Map<String, BarSeries> data, int index) {
        double value = 0;
        for (Map.Entry<String, Double> p : positions.entrySet()) {
            value += p.getValue() * data.get(p.getKey()).close(index);
        }
        return value;
    }
}
