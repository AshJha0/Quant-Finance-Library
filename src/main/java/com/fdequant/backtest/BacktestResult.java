package com.fdequant.backtest;

import java.util.Collections;
import java.util.List;

/**
 * Result of a backtest run: full equity curve (one point per bar), completed
 * trade history, and derived performance metrics.
 */
public final class BacktestResult {

    private final String strategyName;
    private final String symbol;
    private final double[] equityCurve;
    private final List<Trade> trades;
    private final PerformanceMetrics metrics;

    public BacktestResult(String strategyName, String symbol, double[] equityCurve,
                          List<Trade> trades, int periodsPerYear) {
        this.strategyName = strategyName;
        this.symbol = symbol;
        this.equityCurve = equityCurve;
        this.trades = Collections.unmodifiableList(trades);
        this.metrics = PerformanceAnalytics.compute(equityCurve, trades, periodsPerYear);
    }

    public String strategyName()       { return strategyName; }
    public String symbol()             { return symbol; }
    public double[] equityCurve()      { return equityCurve; }
    public List<Trade> trades()        { return trades; }
    public PerformanceMetrics metrics() { return metrics; }

    @Override
    public String toString() {
        PerformanceMetrics m = metrics;
        return String.format(
                "%s on %s: totalReturn=%.2f%%, CAGR=%.2f%%, sharpe=%.2f, sortino=%.2f, calmar=%.2f, "
                        + "maxDD=%.2f%%, profitFactor=%.2f, winRate=%.1f%%, trades=%d",
                strategyName, symbol, m.totalReturn() * 100, m.cagr() * 100, m.sharpeRatio(),
                m.sortinoRatio(), m.calmarRatio(), m.maxDrawdown() * 100, m.profitFactor(),
                m.winRate() * 100, m.tradeCount());
    }
}
