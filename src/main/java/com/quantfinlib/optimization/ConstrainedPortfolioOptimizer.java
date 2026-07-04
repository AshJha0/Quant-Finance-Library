package com.quantfinlib.optimization;

import com.quantfinlib.util.MathUtils;

import java.util.SplittableRandom;
import java.util.function.ToDoubleFunction;

/**
 * Constrained long-only optimizer: per-asset weight bounds (position caps /
 * floors) and an optional turnover penalty against current holdings —
 * {@code adjusted return = μ·w − penalty · Σ|w − w_current|} — so the
 * optimizer trades expected gain against the real cost of getting there.
 * Same derivative-free search as {@link PortfolioOptimizer} with feasibility
 * projection.
 */
public final class ConstrainedPortfolioOptimizer {

    private final double[] expectedReturns;
    private final double[][] covariance;
    private final long seed;
    private double[] minWeights;
    private double[] maxWeights;
    private double[] currentWeights;
    private double turnoverPenalty;

    public ConstrainedPortfolioOptimizer(double[] expectedReturns, double[][] covariance) {
        this(expectedReturns, covariance, 42L);
    }

    public ConstrainedPortfolioOptimizer(double[] expectedReturns, double[][] covariance, long seed) {
        this.expectedReturns = expectedReturns.clone();
        this.covariance = covariance;
        this.seed = seed;
        this.minWeights = new double[expectedReturns.length];
        this.maxWeights = new double[expectedReturns.length];
        java.util.Arrays.fill(maxWeights, 1.0);
    }

    /** Per-asset weight bounds; must admit a fully-invested portfolio. */
    public ConstrainedPortfolioOptimizer withBounds(double[] min, double[] max) {
        double minSum = 0, maxSum = 0;
        for (int i = 0; i < min.length; i++) {
            if (min[i] < 0 || max[i] > 1 || min[i] > max[i]) {
                throw new IllegalArgumentException("bad bounds for asset " + i);
            }
            minSum += min[i];
            maxSum += max[i];
        }
        if (minSum > 1 + 1e-12 || maxSum < 1 - 1e-12) {
            throw new IllegalArgumentException("bounds admit no fully-invested portfolio");
        }
        this.minWeights = min.clone();
        this.maxWeights = max.clone();
        return this;
    }

    /**
     * @param penaltyPerUnitTurnover expected-return units charged per unit of
     *                               one-way turnover (e.g. transaction cost)
     */
    public ConstrainedPortfolioOptimizer withTurnoverPenalty(double[] currentWeights,
                                                             double penaltyPerUnitTurnover) {
        this.currentWeights = currentWeights.clone();
        this.turnoverPenalty = penaltyPerUnitTurnover;
        return this;
    }

    public PortfolioOptimizer.Allocation maxSharpe(double riskFreeRate) {
        double[] w = optimize(x -> {
            double vol = Math.sqrt(Math.max(0, MathUtils.quadraticForm(x, covariance)));
            return vol == 0 ? Double.NEGATIVE_INFINITY
                    : (adjustedReturn(x) - riskFreeRate) / vol;
        });
        return toAllocation(w, riskFreeRate);
    }

    public PortfolioOptimizer.Allocation minVolatility() {
        double[] w = optimize(x ->
                -(Math.sqrt(Math.max(0, MathUtils.quadraticForm(x, covariance)))
                        + turnoverCost(x)));
        return toAllocation(w, 0);
    }

    // ------------------------------------------------------------------

    private double adjustedReturn(double[] w) {
        return MathUtils.dot(expectedReturns, w) - turnoverCost(w);
    }

    private double turnoverCost(double[] w) {
        if (currentWeights == null || turnoverPenalty == 0) {
            return 0;
        }
        double turnover = 0;
        for (int i = 0; i < w.length; i++) {
            turnover += Math.abs(w[i] - currentWeights[i]);
        }
        return turnoverPenalty * turnover;
    }

    private double[] optimize(ToDoubleFunction<double[]> objective) {
        int n = expectedReturns.length;
        SplittableRandom rnd = new SplittableRandom(seed);

        double[] best = project(uniformStart(n));
        double bestScore = objective.applyAsDouble(best);
        double[] cand = new double[n];
        for (int s = 0; s < 20_000; s++) {
            double sum = 0;
            for (int i = 0; i < n; i++) {
                cand[i] = -Math.log(rnd.nextDouble());
                sum += cand[i];
            }
            for (int i = 0; i < n; i++) {
                cand[i] /= sum;
            }
            double[] feasible = project(cand.clone());
            double score = objective.applyAsDouble(feasible);
            if (score > bestScore) {
                bestScore = score;
                best = feasible;
            }
        }
        // Pairwise-transfer refinement within bounds.
        double step = 0.05;
        double[] trial = new double[n];
        for (int sweep = 0; sweep < 80; sweep++) {
            boolean improved = false;
            for (int from = 0; from < n; from++) {
                for (int to = 0; to < n; to++) {
                    if (to == from) {
                        continue;
                    }
                    double move = Math.min(step,
                            Math.min(best[from] - minWeights[from], maxWeights[to] - best[to]));
                    if (move <= 0) {
                        continue;
                    }
                    System.arraycopy(best, 0, trial, 0, n);
                    trial[from] -= move;
                    trial[to] += move;
                    double score = objective.applyAsDouble(trial);
                    if (score > bestScore) {
                        bestScore = score;
                        best = trial.clone();
                        improved = true;
                    }
                }
            }
            if (!improved) {
                step /= 2;
                if (step < 1e-6) {
                    break;
                }
            }
        }
        return best;
    }

    private double[] uniformStart(int n) {
        double[] w = new double[n];
        java.util.Arrays.fill(w, 1.0 / n);
        return w;
    }

    /** Projects onto the box-constrained simplex (clip, then redistribute). */
    private double[] project(double[] w) {
        int n = w.length;
        for (int pass = 0; pass < 100; pass++) {
            double sum = 0;
            for (int i = 0; i < n; i++) {
                w[i] = MathUtils.clamp(w[i], minWeights[i], maxWeights[i]);
                sum += w[i];
            }
            double deficit = 1 - sum;
            if (Math.abs(deficit) < 1e-12) {
                return w;
            }
            // Distribute the deficit proportionally to the available headroom.
            double capacity = 0;
            for (int i = 0; i < n; i++) {
                capacity += deficit > 0 ? maxWeights[i] - w[i] : w[i] - minWeights[i];
            }
            if (capacity <= 0) {
                return w;   // bounds validated at configuration; defensive
            }
            for (int i = 0; i < n; i++) {
                double room = deficit > 0 ? maxWeights[i] - w[i] : w[i] - minWeights[i];
                w[i] += deficit * room / capacity;
            }
        }
        return w;
    }

    private PortfolioOptimizer.Allocation toAllocation(double[] w, double riskFreeRate) {
        double ret = MathUtils.dot(expectedReturns, w);
        double vol = Math.sqrt(Math.max(0, MathUtils.quadraticForm(w, covariance)));
        return new PortfolioOptimizer.Allocation(w, ret, vol,
                vol == 0 ? 0 : (ret - riskFreeRate) / vol);
    }
}
