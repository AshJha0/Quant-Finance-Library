package com.quantfinlib.risk;

import com.quantfinlib.stats.SpecialFunctions;
import com.quantfinlib.stats.Stats;

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
        return Stats.sampleStdDev(returns);
    }

    public static double annualizedVolatility(double[] returns, int periodsPerYear) {
        return volatility(returns) * Math.sqrt(periodsPerYear);
    }

    /**
     * Historical Value at Risk at the given confidence level (e.g. 0.95).
     * Returned as a positive loss fraction; 0 if the quantile is a gain.
     * Uses the linearly interpolated return percentile
     * ({@link Stats#quantile}); {@link TailRisk#varSorted} gives the
     * lower empirical quantile that pairs with the ES estimator.
     */
    public static double historicalVar(double[] returns, double confidence) {
        double q = Stats.quantile(returns, 1 - confidence);
        return Math.max(0, -q);
    }

    /** Parametric (Gaussian) VaR at the given confidence level. */
    public static double parametricVar(double[] returns, double confidence) {
        double mu = Stats.mean(returns);
        double sigma = Stats.sampleStdDev(returns);
        double z = SpecialFunctions.normInv(1 - confidence);
        return Math.max(0, -(mu + z * sigma));
    }

    /**
     * Conditional VaR / expected shortfall of a return series as a positive
     * loss fraction (0 if the tail is a gain): the Acerbi-Tasche tail mean
     * of the losses {@code −r} over the worst {@code (1−c)·n} periods, with
     * the fractional boundary weight described in {@link TailRisk}.
     */
    public static double conditionalVar(double[] returns, double confidence) {
        double[] losses = new double[returns.length];
        for (int i = 0; i < returns.length; i++) {
            losses[i] = -returns[i];
        }
        return Math.max(0, TailRisk.of(losses, confidence).expectedShortfall());
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
        double annualReturn = Stats.mean(returns) * periodsPerYear;
        return (annualReturn - riskFreeRate) / vol;
    }

    /** Annualized Sortino ratio using downside deviation below the periodic MAR. */
    public static double sortinoRatio(double[] returns, double riskFreeRate, int periodsPerYear) {
        double mar = riskFreeRate / periodsPerYear;
        double dd = downsideDeviation(returns, mar) * Math.sqrt(periodsPerYear);
        if (dd == 0) {
            return 0;
        }
        double annualReturn = Stats.mean(returns) * periodsPerYear;
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
        double varB = Stats.sampleVariance(benchmarkReturns);
        if (varB == 0) {
            return 0;
        }
        return Stats.sampleCovariance(assetReturns, benchmarkReturns) / varB;
    }

    public static double correlation(double[] a, double[] b) {
        return Stats.pearsonOrZero(a, b);
    }
}
