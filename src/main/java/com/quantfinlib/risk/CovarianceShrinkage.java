package com.quantfinlib.risk;

/**
 * LEDOIT-WOLF covariance shrinkage (2004, "A well-conditioned estimator
 * for large-dimensional covariance matrices") — the standard fix for the
 * dirty secret of portfolio optimization: the sample covariance matrix is
 * the MAXIMALLY overfit estimate. With N assets and T observations it has
 * N(N+1)/2 free parameters; when T is not a large multiple of N its
 * smallest eigenvalues are far too small and its largest far too large —
 * and a mean-variance optimizer amplifies exactly those errors (it loads
 * up on the directions the matrix WRONGLY calls near-riskless).
 *
 * <p>The estimator shrinks toward the scaled identity {@code mu·I}
 * (mu = average sample variance):</p>
 *
 * <pre>  Sigma* = delta · mu·I + (1 − delta) · S</pre>
 *
 * <p>with the intensity delta chosen FROM THE DATA to minimize expected
 * Frobenius loss: delta = b&#772;²/d², where d² = ||S − mu·I||²_F measures how
 * far the sample matrix is from the target and b&#772;² estimates how much of
 * that distance is pure sampling noise (the average Frobenius distance of
 * single-observation outer products from S, over T², clamped to d²).
 * Intuition: when the data say the sample matrix is mostly noise
 * (T small, N large), delta → 1 and you trust the boring target; when T
 * is huge, delta → 0 and the sample matrix speaks for itself. No tuning
 * parameter to pick — that is the whole appeal.</p>
 *
 * <p>The result is always positive-definite for delta &gt; 0 (a convex
 * combination with mu·I lifts every eigenvalue toward mu), which is what
 * makes it safe to hand to {@code optimization.PortfolioOptimizer} where
 * a raw sample matrix from short history can be singular. Identity
 * target, stated: the constant-correlation target variant trades a bit
 * of bias for structure; this is the well-conditioned workhorse.
 * Research lane, deterministic.</p>
 */
public final class CovarianceShrinkage {

    /**
     * @param matrix    the shrunk covariance Sigma*
     * @param intensity delta in [0, 1] — how far toward the target
     * @param target    mu, the average sample variance (the target's diagonal)
     */
    public record Result(double[][] matrix, double intensity, double target) {
    }

    private CovarianceShrinkage() {
    }

    /**
     * @param returns T×N: {@code returns[t][j]} = period-t return of asset j;
     *                T &ge; 2, N &ge; 1, rectangular, finite
     */
    public static Result ledoitWolf(double[][] returns) {
        int t = returns.length;
        if (t < 2) {
            throw new IllegalArgumentException("need >= 2 observations, got " + t);
        }
        int n = returns[0].length;
        if (n < 1) {
            throw new IllegalArgumentException("need >= 1 asset");
        }
        for (int i = 0; i < t; i++) {
            if (returns[i].length != n) {
                throw new IllegalArgumentException("ragged matrix: row " + i);
            }
            for (int j = 0; j < n; j++) {
                if (!Double.isFinite(returns[i][j])) {
                    throw new IllegalArgumentException("non-finite return at " + i + "," + j);
                }
            }
        }
        double[] mean = new double[n];
        for (double[] row : returns) {
            for (int j = 0; j < n; j++) {
                mean[j] += row[j];
            }
        }
        for (int j = 0; j < n; j++) {
            mean[j] /= t;
        }
        // Demeaned data and the sample covariance S (population, /T — the
        // Ledoit-Wolf convention).
        double[][] x = new double[t][n];
        for (int i = 0; i < t; i++) {
            for (int j = 0; j < n; j++) {
                x[i][j] = returns[i][j] - mean[j];
            }
        }
        double[][] s = new double[n][n];
        for (int a = 0; a < n; a++) {
            for (int b = a; b < n; b++) {
                double sum = 0;
                for (int i = 0; i < t; i++) {
                    sum += x[i][a] * x[i][b];
                }
                s[a][b] = sum / t;
                s[b][a] = s[a][b];
            }
        }
        double mu = 0;
        for (int a = 0; a < n; a++) {
            mu += s[a][a];
        }
        mu /= n;

        // d^2 = ||S - mu I||_F^2 / n   (LW normalize by n; cancels in the ratio
        // as long as both d2 and b2 use the same normalization).
        double d2 = 0;
        for (int a = 0; a < n; a++) {
            for (int b = 0; b < n; b++) {
                double target = a == b ? mu : 0;
                double e = s[a][b] - target;
                d2 += e * e;
            }
        }
        d2 /= n;

        // b^2 = (1/T^2) sum_t ||x_t x_t' - S||_F^2 / n, clamped to d^2.
        double b2 = 0;
        for (int i = 0; i < t; i++) {
            double norm = 0;
            for (int a = 0; a < n; a++) {
                for (int b = 0; b < n; b++) {
                    double e = x[i][a] * x[i][b] - s[a][b];
                    norm += e * e;
                }
            }
            b2 += norm;
        }
        b2 /= ((double) t * t * n);
        b2 = Math.min(b2, d2);

        double delta = d2 > 0 ? b2 / d2 : 1.0; // degenerate S == mu I: any delta identical
        double[][] shrunk = new double[n][n];
        for (int a = 0; a < n; a++) {
            for (int b = 0; b < n; b++) {
                double target = a == b ? mu : 0;
                shrunk[a][b] = delta * target + (1 - delta) * s[a][b];
            }
        }
        return new Result(shrunk, delta, mu);
    }

    /** Convenience: the shrunk matrix only. */
    public static double[][] shrink(double[][] returns) {
        return ledoitWolf(returns).matrix();
    }
}
