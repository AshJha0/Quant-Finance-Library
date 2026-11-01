package com.quantfinlib.optimization;

import com.quantfinlib.risk.PsdMatrices;
import com.quantfinlib.util.MathUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Long-only, fully invested mean-variance optimisation (Markowitz 1952),
 * solved exactly as convex quadratic programs by {@link QuadraticProgram}:
 *
 * <ul>
 *   <li><b>minimum variance</b>: {@code min w'Σw} s.t. {@code 1'w = 1, w ≥ 0};</li>
 *   <li><b>frontier point</b>: the same with {@code μ'w = target} added;</li>
 *   <li><b>maximum Sharpe</b>: by the homogenisation of the linear-fractional
 *       problem (Cornuéjols and Tütüncü, Optimization Methods in Finance,
 *       2007, §8.2): {@code min y'Σy} s.t. {@code (μ − r_f)'y = 1, y ≥ 0},
 *       then {@code w = y / 1'y}. It needs at least one asset with
 *       {@code μ_i > r_f}; otherwise no long-only portfolio has a positive
 *       excess return and the method throws.</li>
 * </ul>
 *
 * <p>Results are optimal to rounding (the active-set method terminates on
 * the KKT conditions). Expected returns and covariance must share the same
 * periodicity. The covariance is copied and must be symmetric and positive
 * semi-definite.</p>
 */
public final class PortfolioOptimizer {

    /** An optimized allocation with its risk/return profile (same periodicity as the inputs). */
    public record Allocation(double[] weights, double expectedReturn, double volatility, double sharpe) {
    }

    private final double[] expectedReturns;
    private final double[][] covariance;

    public PortfolioOptimizer(double[] expectedReturns, double[][] covariance) {
        int n = expectedReturns.length;
        if (n < 1 || covariance.length != n) {
            throw new IllegalArgumentException("returns/covariance dimension mismatch");
        }
        for (double m : expectedReturns) {
            if (!Double.isFinite(m)) {
                throw new IllegalArgumentException("expected returns must be finite");
            }
        }
        PsdMatrices.choleskyPsd(covariance);       // square, symmetric, finite, PSD
        this.expectedReturns = expectedReturns.clone();
        this.covariance = new double[n][];
        for (int i = 0; i < n; i++) {
            this.covariance[i] = covariance[i].clone();
        }
    }

    /**
     * Kept for source compatibility: the solver is deterministic and the
     * seed is ignored.
     */
    public PortfolioOptimizer(double[] expectedReturns, double[][] covariance, long seed) {
        this(expectedReturns, covariance);
    }

    /** Maximum Sharpe ratio portfolio. {@code riskFreeRate} in the same periodicity as the inputs. */
    public Allocation maxSharpe(double riskFreeRate) {
        int n = expectedReturns.length;
        double[] excess = new double[n];
        int best = -1;
        for (int i = 0; i < n; i++) {
            excess[i] = expectedReturns[i] - riskFreeRate;
            if (excess[i] > 0 && (best < 0 || excess[i] > excess[best])) {
                best = i;
            }
        }
        if (best < 0) {
            throw new IllegalArgumentException("no asset has an expected return above the "
                    + "risk-free rate; the long-only tangency portfolio does not exist");
        }
        double[] y0 = new double[n];
        y0[best] = 1 / excess[best];
        QuadraticProgram.Solution s = QuadraticProgram.solve(scaled(2), new double[n],
                new double[][]{excess}, new double[]{1}, negIdentity(n), new double[n], y0);
        double sum = 0;
        for (double v : s.x()) {
            sum += v;
        }
        double[] w = new double[n];
        for (int i = 0; i < n; i++) {
            w[i] = Math.max(0, s.x()[i] / sum);
        }
        return toAllocation(normalise(w), riskFreeRate);
    }

    /** Minimum volatility portfolio. */
    public Allocation minVolatility() {
        int n = expectedReturns.length;
        double[] w0 = new double[n];
        java.util.Arrays.fill(w0, 1.0 / n);
        QuadraticProgram.Solution s = QuadraticProgram.solve(scaled(2), new double[n],
                new double[][]{ones(n)}, new double[]{1}, negIdentity(n), new double[n], w0);
        return toAllocation(normalise(s.x()), 0);
    }

    /**
     * Minimum-variance frontier: for each of {@code points} target returns
     * evenly spaced from the lowest to the highest asset expected return,
     * the long-only portfolio of least variance that earns exactly that
     * return. Points below the minimum-variance portfolio's return are on
     * the inefficient lower branch; filter by return if only the efficient
     * part is wanted.
     */
    public List<Allocation> efficientFrontier(int points) {
        if (points < 1) {
            throw new IllegalArgumentException("points must be >= 1");
        }
        int n = expectedReturns.length;
        int iLo = 0;
        int iHi = 0;
        for (int i = 1; i < n; i++) {
            if (expectedReturns[i] < expectedReturns[iLo]) {
                iLo = i;
            }
            if (expectedReturns[i] > expectedReturns[iHi]) {
                iHi = i;
            }
        }
        double lo = expectedReturns[iLo];
        double hi = expectedReturns[iHi];
        List<Allocation> frontier = new ArrayList<>(points);
        if (!(hi - lo > 1e-12 * Math.max(1, Math.abs(hi)))) {
            Allocation mv = minVolatility();
            for (int p = 0; p < points; p++) {
                frontier.add(mv);
            }
            return frontier;
        }
        double[][] q = scaled(2);
        double[][] a = {ones(n), expectedReturns.clone()};
        for (int p = 0; p < points; p++) {
            double theta = points == 1 ? 0 : (double) p / (points - 1);
            double target = lo + (hi - lo) * theta;
            double[] w0 = new double[n];
            w0[iLo] = 1 - theta;
            w0[iHi] += theta;
            QuadraticProgram.Solution s = QuadraticProgram.solve(q, new double[n], a,
                    new double[]{1, target}, negIdentity(n), new double[n], w0);
            frontier.add(toAllocation(normalise(s.x()), 0));
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

    private double[][] scaled(double factor) {
        int n = covariance.length;
        double[][] q = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                q[i][j] = factor * covariance[i][j];
            }
        }
        return q;
    }

    static double[] ones(int n) {
        double[] o = new double[n];
        java.util.Arrays.fill(o, 1);
        return o;
    }

    static double[][] negIdentity(int n) {
        double[][] g = new double[n][n];
        for (int i = 0; i < n; i++) {
            g[i][i] = -1;
        }
        return g;
    }

    /** Clears rounding-level negatives and renormalises to a sum of exactly one. */
    static double[] normalise(double[] w) {
        double[] out = new double[w.length];
        double sum = 0;
        for (int i = 0; i < w.length; i++) {
            out[i] = Math.max(0, w[i]);
            sum += out[i];
        }
        for (int i = 0; i < w.length; i++) {
            out[i] /= sum;
        }
        return out;
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
