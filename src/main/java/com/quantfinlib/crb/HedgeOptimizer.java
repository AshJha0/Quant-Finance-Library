package com.quantfinlib.crb;

import com.quantfinlib.util.MathUtils;

/**
 * Cost-aware minimum-variance hedging of the central risk book's
 * residual — the question is never "how do we flatten this" (sell
 * everything) but "what is the CHEAPEST basket of liquid instruments
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
 * reduction is worth less than their cost get EXACTLY zero, not a dusty
 * small position. {@code λ = 0} recovers the closed-form minimum-
 * variance hedge (the tests pin that against the normal equations).
 * Research lane, static.</p>
 */
public final class HedgeOptimizer {

    // Generous: near-collinear instruments (0.999-correlated futures)
    // contract slowly under Gauss-Seidel, and a silent max-iteration
    // exit would return a plausible-looking, grossly unconverged hedge.
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

        double[] h = new double[m];
        boolean converged = false;
        for (int iter = 0; iter < MAX_ITERATIONS && !converged; iter++) {
            double maxDelta = 0;
            double maxH = 1;
            for (int i = 0; i < m; i++) {
                double a = gram[i][i];
                if (a < 0) {
                    throw new IllegalArgumentException("covariance is not PSD: "
                            + "instrument " + i + " has L'ΣL = " + a);
                }
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
                double threshold = costWeight * costPerUnit[i] / 2;
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
            // RELATIVE tolerance: notionals run in the millions, and an
            // absolute epsilon there is either never met or means nothing.
            converged = maxDelta < RELATIVE_TOLERANCE * maxH;
        }
        if (!converged) {
            // Near-collinear instruments can defeat Gauss-Seidel — an
            // unconverged hedge that LOOKS like an answer is worse than
            // an exception (drop the redundant twin instrument).
            throw new IllegalStateException(
                    "coordinate descent failed to converge in " + MAX_ITERATIONS
                            + " iterations — hedge instruments may be near-collinear");
        }
        return h;
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
