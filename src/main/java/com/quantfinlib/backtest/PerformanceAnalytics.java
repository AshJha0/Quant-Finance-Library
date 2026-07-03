package com.quantfinlib.backtest;

import com.quantfinlib.risk.RiskMetrics;
import com.quantfinlib.util.MathUtils;

import java.util.List;

/** Computes {@link PerformanceMetrics} from an equity curve and trade history. */
public final class PerformanceAnalytics {

    private PerformanceAnalytics() {
    }

    public static PerformanceMetrics compute(double[] equity, List<Trade> trades, int periodsPerYear) {
        int n = equity.length;
        double start = equity[0];
        double end = equity[n - 1];
        double totalReturn = end / start - 1;

        double cagr = 0;
        if (n > 1 && end > 0 && start > 0) {
            cagr = Math.pow(end / start, (double) periodsPerYear / (n - 1)) - 1;
        }

        double[] rets = new double[Math.max(0, n - 1)];
        for (int i = 1; i < n; i++) {
            rets[i - 1] = equity[i] / equity[i - 1] - 1;
        }

        double annReturn = rets.length == 0 ? 0 : MathUtils.mean(rets) * periodsPerYear;
        double annVol = rets.length == 0 ? 0 : RiskMetrics.annualizedVolatility(rets, periodsPerYear);
        double sharpe = rets.length == 0 ? 0 : RiskMetrics.sharpeRatio(rets, 0, periodsPerYear);
        double sortino = rets.length == 0 ? 0 : RiskMetrics.sortinoRatio(rets, 0, periodsPerYear);
        double maxDd = RiskMetrics.maxDrawdown(equity);
        double calmar = maxDd == 0 ? 0 : cagr / maxDd;

        double grossProfit = 0, grossLoss = 0;
        int wins = 0;
        for (Trade t : trades) {
            if (t.pnl() >= 0) {
                grossProfit += t.pnl();
                if (t.pnl() > 0) {
                    wins++;
                }
            } else {
                grossLoss -= t.pnl();
            }
        }
        double profitFactor = grossLoss == 0
                ? (grossProfit > 0 ? Double.POSITIVE_INFINITY : 0)
                : grossProfit / grossLoss;
        double winRate = trades.isEmpty() ? 0 : (double) wins / trades.size();

        return new PerformanceMetrics(totalReturn, cagr, annReturn, annVol, sharpe, sortino,
                calmar, maxDd, profitFactor, winRate, trades.size(), end);
    }
}
