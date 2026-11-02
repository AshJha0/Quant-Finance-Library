package com.quantfinlib.simulation;

import com.quantfinlib.risk.TailRisk;
import com.quantfinlib.stats.Stats;

import java.util.Arrays;

/**
 * Analytics over Monte Carlo terminal portfolio values: probabilities,
 * VaR/CVaR, confidence intervals and scenario extremes.
 */
public final class SimulationResult {

    private final double initialValue;
    private final double[] sortedFinals;

    public SimulationResult(double initialValue, double[] finalValues) {
        this.initialValue = initialValue;
        this.sortedFinals = finalValues.clone();
        Arrays.sort(this.sortedFinals);
    }

    public double initialValue() {
        return initialValue;
    }

    public int simulations() {
        return sortedFinals.length;
    }

    public double probabilityOfProfit() {
        int wins = 0;
        for (double v : sortedFinals) {
            if (v > initialValue) {
                wins++;
            }
        }
        return (double) wins / sortedFinals.length;
    }

    public double probabilityOfLoss() {
        return 1 - probabilityOfProfit();
    }

    /**
     * VaR at the given confidence as a positive loss fraction of the initial
     * value (0 if the quantile is a gain): the lower empirical quantile of
     * the loss fractions {@code (V0 − V_T)/V0}, as defined in {@link TailRisk}.
     */
    public double valueAtRisk(double confidence) {
        return Math.max(0, TailRisk.varSorted(lossFractions(), confidence));
    }

    /**
     * Expected shortfall (CVaR) as a positive loss fraction: the
     * Acerbi-Tasche tail mean of {@link TailRisk}, which uses the same tail
     * as {@link #valueAtRisk} and is never below it.
     */
    public double conditionalValueAtRisk(double confidence) {
        return Math.max(0, TailRisk.expectedShortfallSorted(lossFractions(), confidence));
    }

    private double[] lossFractions() {
        int n = sortedFinals.length;
        double[] losses = new double[n];
        for (int i = 0; i < n; i++) {
            losses[i] = (initialValue - sortedFinals[n - 1 - i]) / initialValue;
        }
        return losses;
    }

    /** Two-sided confidence interval of terminal value, e.g. level = 0.90 → [p5, p95]. */
    public double[] confidenceInterval(double level) {
        double alpha = (1 - level) / 2;
        return new double[]{
                Stats.quantileSorted(sortedFinals, alpha),
                Stats.quantileSorted(sortedFinals, 1 - alpha)
        };
    }

    public double bestCase() {
        return sortedFinals[sortedFinals.length - 1];
    }

    public double worstCase() {
        return sortedFinals[0];
    }

    public double expectedValue() {
        return Stats.mean(sortedFinals);
    }

    public double medianValue() {
        return Stats.quantileSorted(sortedFinals, 0.5);
    }

    @Override
    public String toString() {
        return String.format(
                "MonteCarlo[%d sims]: expected=%.2f, median=%.2f, pProfit=%.1f%%, VaR95=%.2f%%, "
                        + "CVaR95=%.2f%%, best=%.2f, worst=%.2f",
                simulations(), expectedValue(), medianValue(), probabilityOfProfit() * 100,
                valueAtRisk(0.95) * 100, conditionalValueAtRisk(0.95) * 100, bestCase(), worstCase());
    }
}
