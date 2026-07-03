package com.quantfinlib.risk;

import com.quantfinlib.util.MathUtils;

/**
 * Core quantitative risk metrics. Return-based metrics take simple periodic
 * returns (e.g. daily); VaR/CVaR are reported as positive loss fractions.
 */
public final class RiskMetrics {

    public static final int TRADING_DAYS_PER_YEAR = 252;

    private RiskMetrics() {
    }

    /** Per-period sample volatility. */
    public static double volatility(double[] returns) {
        return MathUtils.stdDev(returns);
    }

    public static double annualizedVolatility(double[] returns, int periodsPerYear) {
        return volatility(returns) * Math.sqrt(periodsPerYear);
    }

    /**
     * Historical Value at Risk at the given confidence level (e.g. 0.95).
     * Returned as a positive loss fraction; 0 if the quantile is a gain.
     */
    public static double historicalVar(double[] returns, double confidence) {
        double q = MathUtils.percentile(returns, 1 - confidence);
        return Math.max(0, -q);
    }

    /** Parametric (Gaussian) VaR at the given confidence level. */
    public static double parametricVar(double[] returns, double confidence) {
        double mu = MathUtils.mean(returns);
        double sigma = MathUtils.stdDev(returns);
        double z = MathUtils.normInv(1 - confidence);
        return Math.max(0, -(mu + z * sigma));
    }

    /**
     * Conditional VaR / Expected Shortfall: mean loss beyond the VaR threshold.
     */
    public static double conditionalVar(double[] returns, double confidence) {
        double threshold = MathUtils.percentile(returns, 1 - confidence);
        double sum = 0;
        int count = 0;
        for (double r : returns) {
            if (r <= threshold) {
                sum += r;
                count++;
            }
        }
        return count == 0 ? 0 : Math.max(0, -sum / count);
    }

    /** Alias for {@link #conditionalVar}. */
    public static double expectedShortfall(double[] returns, double confidence) {
        return conditionalVar(returns, confidence);
    }

    /** Annualized Sharpe ratio; {@code riskFreeRate} is annual. */
    public static double sharpeRatio(double[] returns, double riskFreeRate, int periodsPerYear) {
        double vol = annualizedVolatility(returns, periodsPerYear);
        if (vol == 0) {
            return 0;
        }
        double annualReturn = MathUtils.mean(returns) * periodsPerYear;
        return (annualReturn - riskFreeRate) / vol;
    }

    /** Annualized Sortino ratio using downside deviation below the periodic MAR. */
    public static double sortinoRatio(double[] returns, double riskFreeRate, int periodsPerYear) {
        double mar = riskFreeRate / periodsPerYear;
        double dd = downsideDeviation(returns, mar) * Math.sqrt(periodsPerYear);
        if (dd == 0) {
            return 0;
        }
        double annualReturn = MathUtils.mean(returns) * periodsPerYear;
        return (annualReturn - riskFreeRate) / dd;
    }

    /** Per-period downside deviation below the minimum acceptable return. */
    public static double downsideDeviation(double[] returns, double mar) {
        double s = 0;
        for (double r : returns) {
            double d = Math.min(0, r - mar);
            s += d * d;
        }
        return Math.sqrt(s / returns.length);
    }

    /** Maximum peak-to-trough drawdown of an equity curve, as a positive fraction. */
    public static double maxDrawdown(double[] equity) {
        double peak = equity[0];
        double maxDd = 0;
        for (double e : equity) {
            peak = Math.max(peak, e);
            if (peak > 0) {
                maxDd = Math.max(maxDd, (peak - e) / peak);
            }
        }
        return maxDd;
    }

    /** Beta of an asset versus a benchmark (equal-length return series). */
    public static double beta(double[] assetReturns, double[] benchmarkReturns) {
        double varB = MathUtils.variance(benchmarkReturns);
        if (varB == 0) {
            return 0;
        }
        return MathUtils.covariance(assetReturns, benchmarkReturns) / varB;
    }

    public static double correlation(double[] a, double[] b) {
        return MathUtils.correlation(a, b);
    }
}
