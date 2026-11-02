package com.quantfinlib.hedging;

import com.quantfinlib.risk.TailRisk;
import com.quantfinlib.stats.Stats;

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
        this.mean = Stats.mean(pnls);
        this.std = Stats.sampleStdDev(pnls);
        this.meanTradingCosts = Stats.mean(tradingCosts);
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
        return Stats.quantileSorted(sortedPnls, p);
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

    /**
     * Hedging VaR: loss at the given confidence as a positive number (0 if a
     * gain), the lower empirical quantile of {@code −P&L} ({@link TailRisk}).
     */
    public double valueAtRisk(double confidence) {
        return Math.max(0, TailRisk.varSorted(losses(), confidence));
    }

    /** Expected shortfall of {@code −P&L} (Acerbi-Tasche, {@link TailRisk}), positive. */
    public double conditionalValueAtRisk(double confidence) {
        return Math.max(0, TailRisk.expectedShortfallSorted(losses(), confidence));
    }

    private double[] losses() {
        int n = sortedPnls.length;
        double[] l = new double[n];
        for (int i = 0; i < n; i++) {
            l[i] = -sortedPnls[n - 1 - i];
        }
        return l;
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
