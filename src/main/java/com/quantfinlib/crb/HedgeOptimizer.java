package com.quantfinlib.crb;

import com.quantfinlib.util.MathUtils;

/**
 * Cost-aware minimum-variance hedging of the central risk book's
 * residual — the question is never "how do we flatten this" (sell
 * everything) but "what is the cheapest basket of liquid instruments
 * that takes the risk below the limit". Minimizes
 *
 * <pre>  (e + L·h)' Σ (e + L·h)  +  λ · Σᵢ cᵢ·|hᵢ|</pre>
 *
 * over hedge notionals {@code h}: {@code e} the factor exposures,
 * {@code L} each instrument's factor loadings, {@code Σ} the factor
 * covariance, {@code cᵢ} the instrument's all-in cost per unit notional
 * (spread + expected impact — a {@code KylesLambda} estimate slots in
 * directly), {@code λ} the risk/cost trade-off.
 *
 * <p>Solved by cyclic coordinate descent with the exact soft-threshold
 * update — deterministic, no external optimizer, and the L1 term does
 * what a hedging desk actually wants: instruments whose marginal risk
 * reduction is worth less than their cost get exactly zero, not a dusty
 * small position. {@code λ = 0} recovers the closed-form minimum-
 * variance hedge (the tests pin that against the normal equations).
 * Every 50 sweeps the solver tries to finish exactly: it solves the
 * optimality conditions on the current support and sign pattern and
 * accepts the result if the subgradient condition holds off the support.
 * A ridge of 1e-12·tr(G)/m on the instrument Gram matrix makes the
 * solution unique for collinear instruments. Research lane, static.</p>
 */
public final class HedgeOptimizer {

    // Near-collinear instruments contract slowly under Gauss-Seidel; the
    // periodic support solve finishes them exactly, and a run that still
    // fails throws rather than return an unconverged hedge.
    private static final int MAX_ITERATIONS = 20_000;
    private static final double RELATIVE_TOLERANCE = 1e-10;

    private HedgeOptimizer() {
    }

    /**
     * @param exposures  factor exposures e (length n)
     * @param covariance n×n factor covariance Σ
     * @param loadings   loadings[f][i] — factor f exposure created by one
     *                   unit of instrument i (n × m)
     * @param costPerUnit cᵢ ≥ 0 per unit |notional| (length m)
     * @param costWeight λ ≥ 0 — 0 is pure minimum variance
     * @return hedge notionals h (length m), signed
     */
    public static double[] hedge(double[] exposures, double[][] covariance,
                                 double[][] loadings, double[] costPerUnit,
                                 double costWeight) {
        int n = exposures.length;
        int m = costPerUnit.length;
        requireFinite(exposures, "exposures");
        requireFinite(costPerUnit, "costPerUnit");
        if (!(costWeight >= 0) || costWeight == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("costWeight must be >= 0 and finite");
        }
        if (covariance.length != n || loadings.length != n) {
            throw new IllegalArgumentException("covariance and loadings must have "
                    + n + " factor rows");
        }
        for (double[] row : covariance) {
            if (row.length != n) {
                throw new IllegalArgumentException("covariance must be square");
            }
            // NaN here would slip past every comparison below and come
            // back as a silent all-zero "hedge" for a live breach.
            requireFinite(row, "covariance");
        }
        for (double[] row : loadings) {
            if (row.length != m) {
                throw new IllegalArgumentException("each loadings row needs " + m + " columns");
            }
            requireFinite(row, "loadings");
        }
        for (double c : costPerUnit) {
            if (c < 0) {
                throw new IllegalArgumentException("costs must be >= 0");
            }
        }

        // Precompute per-instrument quadratic terms: a_i = L_i' Σ L_i and
        // the cross terms G[i][j] = L_i' Σ L_j, plus d_i = L_i' Σ e.
        double[][] sigmaL = new double[n][m];
        for (int f = 0; f < n; f++) {
            for (int i = 0; i < m; i++) {
                double s = 0;
                for (int g = 0; g < n; g++) {
                    s += covariance[f][g] * loadings[g][i];
                }
                sigmaL[f][i] = s;
            }
        }
        double[][] gram = new double[m][m];
        double[] d = new double[m];
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < m; j++) {
                double s = 0;
                for (int f = 0; f < n; f++) {
                    s += loadings[f][i] * sigmaL[f][j];
                }
                gram[i][j] = s;
            }
            double s = 0;
            for (int f = 0; f < n; f++) {
                s += loadings[f][i] * matVecRow(covariance, exposures, f);
            }
            d[i] = s;
        }

        // A ridge of 1e-12·tr(G)/m on the diagonal makes the minimiser
        // unique when two instruments are collinear (the twin positions
        // split evenly instead of drifting apart); its effect on a
        // well-posed hedge is far below rounding of the notionals.
        double trace = 0;
        for (int i = 0; i < m; i++) {
            if (gram[i][i] < 0) {
                throw new IllegalArgumentException("covariance is not PSD: "
                        + "instrument " + i + " has L'ΣL = " + gram[i][i]);
            }
            trace += gram[i][i];
        }
        double ridge = m > 0 ? 1e-12 * trace / m : 0;
        for (int i = 0; i < m; i++) {
            gram[i][i] += ridge;
        }
        double[] thresholds = new double[m];
        for (int i = 0; i < m; i++) {
            thresholds[i] = costWeight * costPerUnit[i] / 2;
        }

        double[] h = new double[m];
        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            double maxDelta = 0;
            double maxH = 1;
            for (int i = 0; i < m; i++) {
                double a = gram[i][i];
                if (a == 0) {
                    continue;          // instrument carries no risk: leave at 0
                }
                // b = L_i' Σ (e + Σ_{j≠i} L_j h_j)
                double b = d[i];
                for (int j = 0; j < m; j++) {
                    if (j != i) {
                        b += gram[i][j] * h[j];
                    }
                }
                // minimize a·h² + 2b·h + λc|h|  →  soft threshold at λc/2.
                double threshold = thresholds[i];
                double next;
                if (b > threshold) {
                    next = -(b - threshold) / a;
                } else if (b < -threshold) {
                    next = -(b + threshold) / a;
                } else {
                    next = 0;
                }
                double delta = Math.abs(next - h[i]);
                if (delta > maxDelta) {
                    maxDelta = delta;
                }
                h[i] = next;
                double mag = Math.abs(next);
                if (mag > maxH) {
                    maxH = mag;
                }
            }
            // relative tolerance: notionals run in the millions, and an
            // absolute epsilon there is either never met or means nothing.
            if (maxDelta < RELATIVE_TOLERANCE * maxH) {
                return h;
            }
            // Coordinate descent finds the support quickly but contracts
            // slowly on correlated instruments; every 50 sweeps try to
            // finish exactly on the current support and sign pattern.
            if (iter % 50 == 49) {
                double[] exact = solveOnSupport(h, gram, d, thresholds);
                if (exact != null) {
                    return exact;
                }
            }
        }
        double[] exact = solveOnSupport(h, gram, d, thresholds);
        if (exact != null) {
            return exact;
        }
        throw new IllegalStateException(
                "coordinate descent failed to converge in " + MAX_ITERATIONS
                        + " iterations and the support solve did not satisfy the optimality conditions");
    }

    /**
     * Solves the first-order conditions {@code G_SS·h_S = −(d_S + t_S∘sign)}
     * on the support S of {@code h} and returns the solution if it keeps the
     * signs and satisfies the subgradient condition {@code |d_i + (G·h)_i| <= t_i}
     * off the support; otherwise {@code null}.
     */
    private static double[] solveOnSupport(double[] h, double[][] gram, double[] d,
                                           double[] thresholds) {
        int m = h.length;
        int[] support = new int[m];
        int k = 0;
        for (int i = 0; i < m; i++) {
            if (h[i] != 0) {
                support[k++] = i;
            }
        }
        double[] out = new double[m];
        if (k > 0) {
            double[][] a = new double[k][k];
            double[] rhs = new double[k];
            for (int p = 0; p < k; p++) {
                int i = support[p];
                for (int q = 0; q < k; q++) {
                    a[p][q] = gram[i][support[q]];
                }
                rhs[p] = -(d[i] + thresholds[i] * Math.signum(h[i]));
            }
            double[] x;
            try {
                x = MathUtils.solveLinear(a, rhs);
            } catch (RuntimeException singular) {
                return null;
            }
            for (int p = 0; p < k; p++) {
                int i = support[p];
                if (!Double.isFinite(x[p]) || (x[p] != 0 && Math.signum(x[p]) != Math.signum(h[i]))) {
                    return null;
                }
                out[i] = x[p];
            }
        }
        double scale = 0;
        for (int i = 0; i < m; i++) {
            scale = Math.max(scale, Math.abs(d[i]) + thresholds[i]);
        }
        for (int i = 0; i < m; i++) {
            if (out[i] != 0) {
                continue;
            }
            double g = d[i];
            for (int j = 0; j < m; j++) {
                g += gram[i][j] * out[j];
            }
            if (Math.abs(g) > thresholds[i] + 1e-9 * Math.max(scale, Double.MIN_NORMAL)) {
                return null;
            }
        }
        return out;
    }

    /** Post-hedge factor exposures e + L·h. */
    public static double[] residual(double[] exposures, double[][] loadings, double[] h) {
        int n = exposures.length;
        double[] out = new double[n];
        for (int f = 0; f < n; f++) {
            double s = exposures[f];
            for (int i = 0; i < h.length; i++) {
                s += loadings[f][i] * h[i];
            }
            out[f] = s;
        }
        return out;
    }

    /** Portfolio stdev of an exposure vector under Σ — the risk being cut. */
    public static double risk(double[] exposures, double[][] covariance) {
        return Math.sqrt(Math.max(0, MathUtils.quadraticForm(exposures, covariance)));
    }

    private static double matVecRow(double[][] cov, double[] e, int row) {
        double s = 0;
        for (int g = 0; g < e.length; g++) {
            s += cov[row][g] * e[g];
        }
        return s;
    }

    private static void requireFinite(double[] a, String name) {
        for (double x : a) {
            if (!Double.isFinite(x)) {
                throw new IllegalArgumentException(name + " must be finite");
            }
        }
    }
}
