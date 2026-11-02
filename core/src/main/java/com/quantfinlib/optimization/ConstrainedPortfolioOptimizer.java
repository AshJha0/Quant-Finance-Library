package com.quantfinlib.optimization;

import com.quantfinlib.risk.PsdMatrices;
import com.quantfinlib.stats.LinearAlgebra;
import com.quantfinlib.stats.Numerics;

/**
 * Constrained long-only optimiser: per-asset weight bounds (position caps
 * and floors) and an optional turnover penalty against current holdings,
 * {@code adjusted return = μ'w − τ·Σ|w − w_current|}, so the optimiser
 * trades expected gain against the cost of getting there. Both problems are
 * solved exactly with {@link QuadraticProgram}; turnover is linearised by
 * the split {@code w − w_current = u − v}, {@code u, v ≥ 0}.
 *
 * <ul>
 *   <li>{@link #maxSharpe}: {@code max (μ'w − τ·T(w) − r_f)/√(w'Σw)} over the
 *       bounded simplex, through the homogenisation {@code y = κ·w} of the
 *       linear-fractional program (Cornuéjols and Tütüncü 2007, §8.2):
 *       {@code min y'Σy} s.t. {@code μ'y − τ·1'(u+v) − r_f·κ = 1},
 *       {@code 1'y = κ}, {@code y − u + v = κ·w_current},
 *       {@code κ·lo ≤ y ≤ κ·hi}, {@code u, v, κ ≥ 0}.</li>
 *   <li>{@link #minVolatility}: {@code min √(w'Σw) + τ·T(w)}. Without a
 *       penalty this is the minimum-variance QP. With one, the optimum
 *       solves the QP {@code min ½w'Σw + η·T(w)} at {@code η = τ·σ(w*)}
 *       (equal first-order conditions); η is found by bisection.</li>
 * </ul>
 */
public final class ConstrainedPortfolioOptimizer {

    private final double[] expectedReturns;
    private final double[][] covariance;
    private double[] minWeights;
    private double[] maxWeights;
    private double[] currentWeights;
    private double turnoverPenalty;

    public ConstrainedPortfolioOptimizer(double[] expectedReturns, double[][] covariance) {
        int n = expectedReturns.length;
        if (n < 1 || covariance.length != n) {
            throw new IllegalArgumentException("returns/covariance dimension mismatch");
        }
        PsdMatrices.choleskyPsd(covariance);
        this.expectedReturns = expectedReturns.clone();
        this.covariance = new double[n][];
        for (int i = 0; i < n; i++) {
            this.covariance[i] = covariance[i].clone();
        }
        this.minWeights = new double[n];
        this.maxWeights = new double[n];
        java.util.Arrays.fill(maxWeights, 1.0);
    }

    /** Per-asset weight bounds; must admit a fully-invested portfolio. */
    public ConstrainedPortfolioOptimizer withBounds(double[] min, double[] max) {
        if (min.length != expectedReturns.length || max.length != expectedReturns.length) {
            throw new IllegalArgumentException("bounds must have one entry per asset");
        }
        double minSum = 0, maxSum = 0;
        for (int i = 0; i < min.length; i++) {
            if (!(min[i] >= 0) || !(max[i] <= 1) || min[i] > max[i]) {
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
     * Adds a turnover penalty relative to the current weights.
     *
     * @param penaltyPerUnitTurnover expected-return units charged per unit of
     *                               one-way turnover (e.g. transaction cost), &ge; 0
     */
    public ConstrainedPortfolioOptimizer withTurnoverPenalty(double[] currentWeights,
                                                             double penaltyPerUnitTurnover) {
        if (currentWeights.length != expectedReturns.length) {
            throw new IllegalArgumentException("current weights must have one entry per asset");
        }
        if (!(penaltyPerUnitTurnover >= 0) || penaltyPerUnitTurnover == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("penalty must be >= 0 and finite");
        }
        this.currentWeights = currentWeights.clone();
        this.turnoverPenalty = penaltyPerUnitTurnover;
        return this;
    }

    public PortfolioOptimizer.Allocation maxSharpe(double riskFreeRate) {
        int n = expectedReturns.length;
        boolean turnover = hasTurnover();
        double[] wStart = bestStart(riskFreeRate);
        double excess0 = adjustedReturn(wStart) - riskFreeRate;
        if (!(excess0 > 0)) {
            throw new IllegalArgumentException("no feasible portfolio has a positive "
                    + "turnover-adjusted excess return; the tangency portfolio does not exist");
        }
        // Variable layout: y[0..n), (u[n..2n), v[2n..3n) if turnover), κ last.
        int dim = turnover ? 3 * n + 1 : n + 1;
        int kIdx = dim - 1;
        double[][] q = new double[dim][dim];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                q[i][j] = 2 * covariance[i][j];
            }
        }
        int eqRows = turnover ? 2 + n : 2;
        double[][] a = new double[eqRows][dim];
        double[] b = new double[eqRows];
        for (int i = 0; i < n; i++) {
            a[0][i] = expectedReturns[i];
            a[1][i] = 1;
        }
        a[0][kIdx] = -riskFreeRate;
        b[0] = 1;
        a[1][kIdx] = -1;
        if (turnover) {
            for (int i = 0; i < n; i++) {
                a[0][n + i] = -turnoverPenalty;
                a[0][2 * n + i] = -turnoverPenalty;
                a[2 + i][i] = 1;
                a[2 + i][n + i] = -1;
                a[2 + i][2 * n + i] = 1;
                a[2 + i][kIdx] = -currentWeights[i];
            }
        }
        int ineq = 2 * n + 1 + (turnover ? 2 * n : 0);
        double[][] g = new double[ineq][dim];
        int r = 0;
        for (int i = 0; i < n; i++) {
            g[r][i] = -1;
            g[r++][kIdx] = minWeights[i];
            g[r][i] = 1;
            g[r++][kIdx] = -maxWeights[i];
        }
        if (turnover) {
            for (int i = 0; i < 2 * n; i++) {
                g[r++][n + i] = -1;
            }
        }
        g[r][kIdx] = -1;
        double kappa = 1 / excess0;
        double[] x0 = new double[dim];
        for (int i = 0; i < n; i++) {
            x0[i] = kappa * wStart[i];
            if (turnover) {
                double d = wStart[i] - currentWeights[i];
                x0[n + i] = kappa * Math.max(0, d);
                x0[2 * n + i] = kappa * Math.max(0, -d);
            }
        }
        x0[kIdx] = kappa;
        QuadraticProgram.Solution s = QuadraticProgram.solve(q, new double[dim], a, b, g,
                new double[ineq], x0);
        double[] w = new double[n];
        for (int i = 0; i < n; i++) {
            w[i] = s.x(i) / s.x(kIdx);
        }
        return toAllocation(clipToBounds(w), riskFreeRate);
    }

    public PortfolioOptimizer.Allocation minVolatility() {
        if (!hasTurnover()) {
            return toAllocation(clipToBounds(penalisedMinVariance(0)), 0);
        }
        // Bisection on η for the fixed point η = τ·σ(w(η)); σ ≤ max_i σ_i.
        double maxVol = 0;
        for (int i = 0; i < covariance.length; i++) {
            maxVol = Math.max(maxVol, Math.sqrt(covariance[i][i]));
        }
        double lo = 0;
        double hi = turnoverPenalty * maxVol;
        double[] w = penalisedMinVariance(hi);
        for (int it = 0; it < 200 && hi - lo > 1e-15 * Math.max(hi, 1e-300); it++) {
            double mid = 0.5 * (lo + hi);
            w = penalisedMinVariance(mid);
            if (mid - turnoverPenalty * volatility(w) < 0) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return toAllocation(clipToBounds(penalisedMinVariance(0.5 * (lo + hi))), 0);
    }

    // ------------------------------------------------------------------

    private boolean hasTurnover() {
        return currentWeights != null && turnoverPenalty > 0;
    }

    /** {@code min ½w'Σw + η·Σ|w − w_current|} on the bounded simplex. */
    private double[] penalisedMinVariance(double eta) {
        int n = expectedReturns.length;
        boolean turnover = hasTurnover();
        int dim = turnover ? 3 * n : n;
        double[][] q = new double[dim][dim];
        for (int i = 0; i < n; i++) {
            System.arraycopy(covariance[i], 0, q[i], 0, n);
        }
        double[] c = new double[dim];
        int eqRows = turnover ? 1 + n : 1;
        double[][] a = new double[eqRows][dim];
        double[] b = new double[eqRows];
        for (int i = 0; i < n; i++) {
            a[0][i] = 1;
        }
        b[0] = 1;
        if (turnover) {
            for (int i = 0; i < n; i++) {
                c[n + i] = eta;
                c[2 * n + i] = eta;
                a[1 + i][i] = 1;
                a[1 + i][n + i] = -1;
                a[1 + i][2 * n + i] = 1;
                b[1 + i] = currentWeights[i];
            }
        }
        int ineq = 2 * n + (turnover ? 2 * n : 0);
        double[][] g = new double[ineq][dim];
        double[] h = new double[ineq];
        int r = 0;
        for (int i = 0; i < n; i++) {
            g[r][i] = -1;
            h[r++] = -minWeights[i];
            g[r][i] = 1;
            h[r++] = maxWeights[i];
        }
        if (turnover) {
            for (int i = 0; i < 2 * n; i++) {
                g[r++][n + i] = -1;
            }
        }
        double[] w0 = projectToBoundedSimplex(uniform(n));
        double[] x0 = new double[dim];
        System.arraycopy(w0, 0, x0, 0, n);
        if (turnover) {
            for (int i = 0; i < n; i++) {
                double d = w0[i] - currentWeights[i];
                x0[n + i] = Math.max(0, d);
                x0[2 * n + i] = Math.max(0, -d);
            }
        }
        double[] x = QuadraticProgram.solve(q, c, a, b, g, h, x0).xCopy();
        return java.util.Arrays.copyOf(x, n);
    }

    /**
     * A feasible start with the largest turnover-adjusted excess return
     * among: the bounded portfolio maximising μ'w (greedy fill of the best
     * assets above their floors), the current holdings projected onto the
     * bounds, and points between them.
     */
    private double[] bestStart(double rf) {
        int n = expectedReturns.length;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (x, y) -> Double.compare(expectedReturns[y], expectedReturns[x]));
        double[] greedy = minWeights.clone();
        double room = 1;
        for (double m : minWeights) {
            room -= m;
        }
        for (int k = 0; k < n && room > 0; k++) {
            int i = order[k];
            double add = Math.min(room, maxWeights[i] - greedy[i]);
            greedy[i] += add;
            room -= add;
        }
        double[] anchor = currentWeights != null
                ? projectToBoundedSimplex(currentWeights.clone()) : greedy;
        double[] best = greedy;
        double bestVal = adjustedReturn(greedy) - rf;
        for (int k = 0; k <= 40; k++) {
            double t = k / 40.0;
            double[] w = new double[n];
            for (int i = 0; i < n; i++) {
                w[i] = (1 - t) * greedy[i] + t * anchor[i];
            }
            double v = adjustedReturn(w) - rf;
            if (v > bestVal) {
                bestVal = v;
                best = w;
            }
        }
        return best;
    }

    private double adjustedReturn(double[] w) {
        double r = LinearAlgebra.dot(expectedReturns, w);
        if (hasTurnover()) {
            double t = 0;
            for (int i = 0; i < w.length; i++) {
                t += Math.abs(w[i] - currentWeights[i]);
            }
            r -= turnoverPenalty * t;
        }
        return r;
    }

    private static double[] uniform(int n) {
        double[] w = new double[n];
        java.util.Arrays.fill(w, 1.0 / n);
        return w;
    }

    /**
     * Euclidean projection onto {lo ≤ w ≤ hi, 1'w = 1}: {@code w_i =
     * clamp(v_i − τ, lo_i, hi_i)} with τ found by bisection so the weights
     * sum to one (the projection is unique and idempotent).
     */
    double[] projectToBoundedSimplex(double[] v) {
        int n = v.length;
        double tLo = Double.POSITIVE_INFINITY;
        double tHi = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            tLo = Math.min(tLo, v[i] - maxWeights[i]);
            tHi = Math.max(tHi, v[i] - minWeights[i]);
        }
        double[] w = new double[n];
        for (int it = 0; it < 200; it++) {
            double t = 0.5 * (tLo + tHi);
            double sum = 0;
            for (int i = 0; i < n; i++) {
                sum += Numerics.clamp(v[i] - t, minWeights[i], maxWeights[i]);
            }
            if (sum > 1) {
                tLo = t;
            } else {
                tHi = t;
            }
        }
        double t = 0.5 * (tLo + tHi);
        for (int i = 0; i < n; i++) {
            w[i] = Numerics.clamp(v[i] - t, minWeights[i], maxWeights[i]);
        }
        return w;
    }

    /** Removes rounding outside the bounds left by the solver. */
    private double[] clipToBounds(double[] w) {
        double[] out = new double[w.length];
        for (int i = 0; i < w.length; i++) {
            out[i] = Numerics.clamp(w[i], minWeights[i], maxWeights[i]);
        }
        return out;
    }

    private double volatility(double[] w) {
        return Math.sqrt(Math.max(0, LinearAlgebra.quadraticForm(w, covariance)));
    }

    private PortfolioOptimizer.Allocation toAllocation(double[] w, double riskFreeRate) {
        double ret = LinearAlgebra.dot(expectedReturns, w);
        double vol = volatility(w);
        return new PortfolioOptimizer.Allocation(w, ret, vol,
                vol == 0 ? 0 : (ret - riskFreeRate) / vol);
    }
}
