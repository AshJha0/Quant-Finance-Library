package com.quantfinlib.hedging;

import com.quantfinlib.util.MathUtils;

import java.util.Arrays;
import java.util.Locale;

/**
 * Distribution of delta-hedging P&amp;L across Monte Carlo paths: how well the
 * hedge replicates the option, where the tail risk sits, and what the hedging
 * itself costs. P&amp;L is the replication error of the short-option hedge
 * (positive = the hedge portfolio beat the payoff).
 */
public final class HedgingErrorDistribution {

    private final double premium;
    private final double[] sortedPnls;
    private final double mean;
    private final double std;
    private final double meanTradingCosts;
    private final double meanRebalances;

    HedgingErrorDistribution(double premium, double[] pnls, double[] tradingCosts, int[] rebalances) {
        this.premium = premium;
        this.sortedPnls = pnls.clone();
        Arrays.sort(this.sortedPnls);
        this.mean = MathUtils.mean(pnls);
        this.std = MathUtils.stdDev(pnls);
        this.meanTradingCosts = MathUtils.mean(tradingCosts);
        double r = 0;
        for (int x : rebalances) {
            r += x;
        }
        this.meanRebalances = r / rebalances.length;
    }

    public int paths()                { return sortedPnls.length; }
    public double premium()           { return premium; }
    public double mean()              { return mean; }
    public double stdDev()            { return std; }
    public double meanTradingCosts()  { return meanTradingCosts; }
    public double meanRebalances()    { return meanRebalances; }
    public double worst()             { return sortedPnls[0]; }
    public double best()              { return sortedPnls[sortedPnls.length - 1]; }

    public double percentile(double p) {
        return MathUtils.percentileSorted(sortedPnls, p);
    }

    /** Fraction of paths where the hedge lost money. */
    public double probabilityOfLoss() {
        int losses = 0;
        for (double pnl : sortedPnls) {
            if (pnl < 0) {
                losses++;
            }
        }
        return (double) losses / sortedPnls.length;
    }

    /** Hedging VaR: loss at the given confidence, as a positive number (0 if a gain). */
    public double valueAtRisk(double confidence) {
        return Math.max(0, -percentile(1 - confidence));
    }

    /** Expected loss beyond the VaR quantile, as a positive number. */
    public double conditionalValueAtRisk(double confidence) {
        int tail = Math.max(1, (int) Math.floor((1 - confidence) * sortedPnls.length));
        double sum = 0;
        for (int i = 0; i < tail; i++) {
            sum += sortedPnls[i];
        }
        return Math.max(0, -sum / tail);
    }

    /** Replication error as a fraction of the premium (std / premium). */
    public double relativeHedgeError() {
        return premium == 0 ? Double.NaN : std / premium;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT,
                "HedgingError[%d paths]: premium=%.4f mean=%.4f std=%.4f (%.1f%% of premium) "
                        + "pLoss=%.1f%% VaR95=%.4f CVaR95=%.4f costs=%.4f rebalances=%.1f",
                paths(), premium, mean, std, relativeHedgeError() * 100,
                probabilityOfLoss() * 100, valueAtRisk(0.95), conditionalValueAtRisk(0.95),
                meanTradingCosts, meanRebalances);
    }
}
