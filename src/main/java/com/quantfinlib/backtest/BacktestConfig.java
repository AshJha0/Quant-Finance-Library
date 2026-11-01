package com.quantfinlib.backtest;

/**
 * Backtest execution parameters. Rates are fractions (0.001 = 10 bps).
 * {@code slippageRate} is applied to the fill price against the trader
 * (buys fill higher, sells lower) and stands for half-spread plus
 * implementation shortfall; {@code commissionRate} is charged on notional.
 * {@code stopLossPct}/{@code takeProfitPct} of 0 disable the respective
 * check; a strategy's own stop/take-profit settings override zeros here.
 *
 * @param fillPolicy when a signal decided at a bar's close fills
 *                   (default {@link FillPolicy#NEXT_OPEN})
 */
public record BacktestConfig(
        double initialCapital,
        double commissionRate,
        double slippageRate,
        double stopLossPct,
        double takeProfitPct,
        int periodsPerYear,
        FillPolicy fillPolicy) {

    public BacktestConfig {
        if (fillPolicy == null) {
            throw new IllegalArgumentException("fillPolicy must not be null");
        }
        if (initialCapital < 0 || commissionRate < 0 || slippageRate < 0 || stopLossPct < 0
                || takeProfitPct < 0 || periodsPerYear <= 0) {
            throw new IllegalArgumentException("invalid backtest config");
        }
    }

    /** Next-open fills. */
    public BacktestConfig(double initialCapital, double commissionRate, double slippageRate,
                          double stopLossPct, double takeProfitPct, int periodsPerYear) {
        this(initialCapital, commissionRate, slippageRate, stopLossPct, takeProfitPct,
                periodsPerYear, FillPolicy.NEXT_OPEN);
    }

    /**
     * Daily-bar defaults matching {@code TradeCostModel.institutional(1, 2, 1, …)}
     * without impact: 1 bp commission, 3 bp slippage (2 bp half-spread plus
     * 1 bp shortfall), next-open fills, 100,000 capital.
     */
    public static BacktestConfig defaults() {
        return new BacktestConfig(100_000, 0.0001, 0.0003, 0.0, 0.0, 252, FillPolicy.NEXT_OPEN);
    }

    /** No commission, no slippage, next-open fills: for unit tests and attribution of costs. */
    public static BacktestConfig frictionless() {
        return new BacktestConfig(100_000, 0, 0, 0.0, 0.0, 252, FillPolicy.NEXT_OPEN);
    }

    public BacktestConfig withInitialCapital(double capital) {
        return new BacktestConfig(capital, commissionRate, slippageRate, stopLossPct, takeProfitPct,
                periodsPerYear, fillPolicy);
    }

    public BacktestConfig withCommission(double rate) {
        return new BacktestConfig(initialCapital, rate, slippageRate, stopLossPct, takeProfitPct,
                periodsPerYear, fillPolicy);
    }

    public BacktestConfig withSlippage(double rate) {
        return new BacktestConfig(initialCapital, commissionRate, rate, stopLossPct, takeProfitPct,
                periodsPerYear, fillPolicy);
    }

    public BacktestConfig withStopLoss(double pct) {
        return new BacktestConfig(initialCapital, commissionRate, slippageRate, pct, takeProfitPct,
                periodsPerYear, fillPolicy);
    }

    public BacktestConfig withTakeProfit(double pct) {
        return new BacktestConfig(initialCapital, commissionRate, slippageRate, stopLossPct, pct,
                periodsPerYear, fillPolicy);
    }

    public BacktestConfig withFillPolicy(FillPolicy policy) {
        return new BacktestConfig(initialCapital, commissionRate, slippageRate, stopLossPct,
                takeProfitPct, periodsPerYear, policy);
    }
}
