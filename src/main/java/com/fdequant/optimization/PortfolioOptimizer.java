package com.fdequant.optimization;

import com.fdequant.util.MathUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.ToDoubleFunction;

/**
 * Portfolio Optimization Engine (long-only, fully invested).
 *
 * <p>Supports maximum-Sharpe and minimum-volatility portfolios plus efficient
 * frontier construction. The optimizer uses stochastic search over the simplex
 * (Dirichlet sampling) followed by deterministic pairwise-transfer refinement,
 * which is robust for non-smooth objectives and needs no external solver.
 * Results are deterministic for a given seed.</p>
 *
 * <p>Expected returns and covariance must share the same periodicity
 * (e.g. both annualized, or both daily).</p>
 */
public final class PortfolioOptimizer {

    /** An optimized allocation with its risk/return profile (same periodicity as the inputs). */
    public record Allocation(double[] weights, double expectedReturn, double volatility, double sharpe) {
    }

    private static final int SAMPLES = 20_000;
    private static final int REFINE_SWEEPS = 60;

    private final double[] expectedReturns;
    private final double[][] covariance;
    private final long seed;

    public PortfolioOptimizer(double[] expectedReturns, double[][] covariance) {
        this(expectedReturns, covariance, 42L);
    }

    public PortfolioOptimizer(double[] expectedReturns, double[][] covariance, long seed) {
        if (expectedReturns.length != covariance.length) {
            throw new IllegalArgumentException("returns/covariance dimension mismatch");
        }
        this.expectedReturns = expectedReturns.clone();
        this.covariance = covariance;
        this.seed = seed;
    }

    /** Maximum Sharpe ratio portfolio. {@code riskFreeRate} in the same periodicity as the inputs. */
    public Allocation maxSharpe(double riskFreeRate) {
        double[] w = optimize(x -> {
            double vol = volatilityOf(x);
            return vol == 0 ? Double.NEGATIVE_INFINITY
                    : (MathUtils.dot(expectedReturns, x) - riskFreeRate) / vol;
        });
        return toAllocation(w, riskFreeRate);
    }

    /** Minimum volatility portfolio. */
    public Allocation minVolatility() {
        double[] w = optimize(x -> -volatilityOf(x));
        return toAllocation(w, 0);
    }

    /**
     * Efficient frontier: minimum-volatility portfolios across a grid of
     * target returns between the min and max asset expected returns.
     */
    public List<Allocation> efficientFrontier(int points) {
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        for (double r : expectedReturns) {
            lo = Math.min(lo, r);
            hi = Math.max(hi, r);
        }
        List<Allocation> frontier = new ArrayList<>(points);
        for (int p = 0; p < points; p++) {
            double target = lo + (hi - lo) * p / Math.max(1, points - 1);
            // Penalized objective: minimize vol subject to hitting the target return.
            double[] w = optimize(x -> {
                double shortfall = target - MathUtils.dot(expectedReturns, x);
                double penalty = shortfall > 0 ? shortfall * 100 : 0;
                return -(volatilityOf(x) + penalty);
            });
            frontier.add(toAllocation(w, 0));
        }
        return frontier;
    }

    /** Rebalancing deltas: target minus current weights, per asset. */
    public static double[] rebalance(double[] currentWeights, double[] targetWeights) {
        double[] d = new double[currentWeights.length];
        for (int i = 0; i < d.length; i++) {
            d[i] = targetWeights[i] - currentWeights[i];
        }
        return d;
    }

    // ------------------------------------------------------------------

    private double[] optimize(ToDoubleFunction<double[]> objective) {
        int n = expectedReturns.length;
        SplittableRandom rnd = new SplittableRandom(seed);

        // Phase 1: Dirichlet sampling over the simplex.
        double[] best = new double[n];
        java.util.Arrays.fill(best, 1.0 / n);
        double bestScore = objective.applyAsDouble(best);
        double[] cand = new double[n];
        for (int s = 0; s < SAMPLES; s++) {
            double sum = 0;
            for (int i = 0; i < n; i++) {
                cand[i] = -Math.log(rnd.nextDouble());
                sum += cand[i];
            }
            for (int i = 0; i < n; i++) {
                cand[i] /= sum;
            }
            double score = objective.applyAsDouble(cand);
            if (score > bestScore) {
                bestScore = score;
                System.arraycopy(cand, 0, best, 0, n);
            }
        }

        // Phase 2: deterministic pairwise-transfer hill climbing with shrinking step.
        double step = 0.10;
        double[] trial = new double[n];
        for (int sweep = 0; sweep < REFINE_SWEEPS; sweep++) {
            boolean improved = false;
            for (int from = 0; from < n; from++) {
                if (best[from] < 1e-12) {
                    continue;
                }
                for (int to = 0; to < n; to++) {
                    if (to == from) {
                        continue;
                    }
                    double move = Math.min(step, best[from]);
                    System.arraycopy(best, 0, trial, 0, n);
                    trial[from] -= move;
                    trial[to] += move;
                    double score = objective.applyAsDouble(trial);
                    if (score > bestScore) {
                        bestScore = score;
                        System.arraycopy(trial, 0, best, 0, n);
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

    private double volatilityOf(double[] w) {
        return Math.sqrt(Math.max(0, MathUtils.quadraticForm(w, covariance)));
    }

    private Allocation toAllocation(double[] w, double riskFreeRate) {
        double ret = MathUtils.dot(expectedReturns, w);
        double vol = volatilityOf(w);
        double sharpe = vol == 0 ? 0 : (ret - riskFreeRate) / vol;
        return new Allocation(w, ret, vol, sharpe);
    }
}
