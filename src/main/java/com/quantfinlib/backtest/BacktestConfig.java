package com.quantfinlib.backtest;

/**
 * Backtest execution parameters. Rates are fractions (0.001 = 10 bps).
 * {@code stopLossPct}/{@code takeProfitPct} of 0 disable the respective check;
 * a strategy's own stop/take-profit settings override zeros here.
 */
public record BacktestConfig(
        double initialCapital,
        double commissionRate,
        double slippageRate,
        double stopLossPct,
        double takeProfitPct,
        int periodsPerYear) {

    public static BacktestConfig defaults() {
        return new BacktestConfig(100_000, 0.001, 0.0, 0.0, 0.0, 252);
    }

    public BacktestConfig withInitialCapital(double capital) {
        return new BacktestConfig(capital, commissionRate, slippageRate, stopLossPct, takeProfitPct, periodsPerYear);
    }

    public BacktestConfig withCommission(double rate) {
        return new BacktestConfig(initialCapital, rate, slippageRate, stopLossPct, takeProfitPct, periodsPerYear);
    }

    public BacktestConfig withStopLoss(double pct) {
        return new BacktestConfig(initialCapital, commissionRate, slippageRate, pct, takeProfitPct, periodsPerYear);
    }

    public BacktestConfig withTakeProfit(double pct) {
        return new BacktestConfig(initialCapital, commissionRate, slippageRate, stopLossPct, pct, periodsPerYear);
    }
}
