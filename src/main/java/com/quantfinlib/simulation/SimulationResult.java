package com.quantfinlib.simulation;

import com.quantfinlib.util.MathUtils;

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

    /** VaR at the given confidence as a positive loss fraction of the initial value. */
    public double valueAtRisk(double confidence) {
        double q = MathUtils.percentileSorted(sortedFinals, 1 - confidence);
        return Math.max(0, (initialValue - q) / initialValue);
    }

    /** CVaR: average loss fraction in the tail beyond the VaR quantile. */
    public double conditionalValueAtRisk(double confidence) {
        int tail = Math.max(1, (int) Math.floor((1 - confidence) * sortedFinals.length));
        double sum = 0;
        for (int i = 0; i < tail; i++) {
            sum += sortedFinals[i];
        }
        double avgTail = sum / tail;
        return Math.max(0, (initialValue - avgTail) / initialValue);
    }

    /** Two-sided confidence interval of terminal value, e.g. level = 0.90 → [p5, p95]. */
    public double[] confidenceInterval(double level) {
        double alpha = (1 - level) / 2;
        return new double[]{
                MathUtils.percentileSorted(sortedFinals, alpha),
                MathUtils.percentileSorted(sortedFinals, 1 - alpha)
        };
    }

    public double bestCase() {
        return sortedFinals[sortedFinals.length - 1];
    }

    public double worstCase() {
        return sortedFinals[0];
    }

    public double expectedValue() {
        return MathUtils.mean(sortedFinals);
    }

    public double medianValue() {
        return MathUtils.percentileSorted(sortedFinals, 0.5);
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
