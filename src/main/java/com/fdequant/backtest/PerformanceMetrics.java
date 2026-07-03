package com.fdequant.backtest;

/**
 * Strategy performance analytics. Returns/drawdown are fractions; ratios are
 * annualized.
 */
public record PerformanceMetrics(
        double totalReturn,
        double cagr,
        double annualizedReturn,
        double annualizedVolatility,
        double sharpeRatio,
        double sortinoRatio,
        double calmarRatio,
        double maxDrawdown,
        double profitFactor,
        double winRate,
        int tradeCount,
        double finalEquity) {
}
