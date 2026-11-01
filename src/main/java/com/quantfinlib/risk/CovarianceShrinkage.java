package com.quantfinlib.risk;

import com.quantfinlib.util.ValueArrays;

/**
 * Ledoit-Wolf covariance shrinkage (2004, "A well-conditioned estimator
 * for large-dimensional covariance matrices"): the standard fix for the
 * dirty secret of portfolio optimization. The sample covariance matrix is
 * the maximally overfit estimate. With N assets and T observations it has
 * N(N+1)/2 free parameters; when T is not a large multiple of N its
 * smallest eigenvalues are far too small and its largest far too large,
 * and a mean-variance optimizer amplifies exactly those errors (it loads
 * up on the directions the matrix wrongly calls near-riskless).
 *
 * <p>The estimator shrinks toward the scaled identity {@code mu·I}
 * (mu = average sample variance):</p>
 *
 * <pre>  Sigma* = delta · mu·I + (1 − delta) · S</pre>
 *
 * <p>with the intensity delta chosen from the data to minimize expected
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
 * a raw sample matrix from short history can be singular.
 * {@link #constantCorrelation} shrinks instead toward the
 * constant-correlation target of Ledoit and Wolf (2003/2004, "Honey, I
 * shrunk the sample covariance matrix", J. Portfolio Management 30(4)),
 * which keeps each asset's own variance and replaces every correlation by
 * the average one; it is the better target for equity books where all
 * names share a market factor.</p>
 *
 * <p>Units: both estimators return the population covariance (divisor T,
 * the Ledoit-Wolf convention), while {@code CorrelationMatrix.covariance}
 * uses the sample divisor T − 1. Do not mix the two in one risk number; the
 * ratio is T/(T − 1) (1.7% at T = 60). Research lane, deterministic.</p>
 */
public final class CovarianceShrinkage {

    public static final class Result {
        private final double[][] matrix;
        private final double intensity;
        private final double target;

        /**
         * Creates the value; array and collection arguments are copied.
         *
         * @param matrix the shrunk covariance Sigma*
         * @param intensity delta in [0, 1] — how far toward the target
         * @param target identity target: mu, the average sample variance (the target's diagonal); constant-correlation target: the average sample correlation r̄
         */
        public Result(double[][] matrix, double intensity, double target) {
            this.matrix = ValueArrays.copy(matrix);
            this.intensity = intensity;
            this.target = target;
        }

        /** Element {@code [i][j]} of {@code matrix} (the shrunk covariance Sigma*). */
        public double matrix(int i, int j) {
            return matrix[i][j];
        }

        /** Number of rows of {@code matrix}. */
        public int matrixRows() {
            return matrix.length;
        }

        /** A deep copy of {@code matrix}. */
        public double[][] matrixCopy() {
            return ValueArrays.copy(matrix);
        }

        /** {@code intensity} (delta in [0, 1] — how far toward the target). */
        public double intensity() {
            return intensity;
        }

        /** {@code target} (identity target: mu, the average sample variance (the target's diagonal); constant-correlation target: the average sample correlation r̄). */
        public double target() {
            return target;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Result o)) {
                return false;
            }
            return java.util.Arrays.deepEquals(matrix, o.matrix)
                    && Double.compare(intensity, o.intensity) == 0
                    && Double.compare(target, o.target) == 0;
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Arrays.deepHashCode(matrix);
            h = 31 * h + Double.hashCode(intensity);
            h = 31 * h + Double.hashCode(target);
            return h;
        }

        @Override
        public String toString() {
            return "Result[" + "matrix=" + java.util.Arrays.deepToString(matrix) + ", " + "intensity=" + intensity + ", " + "target=" + target + "]";
        }
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

    /**
     * Ledoit-Wolf shrinkage toward the constant-correlation target
     * {@code F_ii = s_ii}, {@code F_ij = r̄·√(s_ii s_jj)} with r̄ the average
     * sample correlation. The optimal intensity is
     * {@code δ = max(0, min(1, (π̂ − ρ̂)/(γ̂·T)))} with (LW 2004, Appendix B)
     * <pre>  π̂ = Σ_ij (1/T) Σ_t (x_ti x_tj − s_ij)²
     *  ρ̂ = Σ_i π̂_ii + Σ_{i≠j} (r̄/2)·( √(s_jj/s_ii)·θ_ii,ij + √(s_ii/s_jj)·θ_jj,ij )
     *  θ_ii,ij = (1/T) Σ_t (x_ti² − s_ii)(x_ti x_tj − s_ij)
     *  γ̂ = ||F − S||²_F</pre>
     * on demeaned data x. {@link Result#target()} reports r̄.
     *
     * @param returns T×N, T &ge; 2, N &ge; 2, rectangular, finite, every
     *                asset with positive sample variance
     */
    public static Result constantCorrelation(double[][] returns) {
        int t = returns.length;
        if (t < 2) {
            throw new IllegalArgumentException("need >= 2 observations, got " + t);
        }
        int n = returns[0].length;
        if (n < 2) {
            throw new IllegalArgumentException("need >= 2 assets for a correlation target");
        }
        double[][] x = demeaned(returns, n);
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
        double[] sd = new double[n];
        for (int a = 0; a < n; a++) {
            if (!(s[a][a] > 0)) {
                throw new IllegalArgumentException("asset " + a + " has zero variance");
            }
            sd[a] = Math.sqrt(s[a][a]);
        }
        double rSum = 0;
        for (int a = 0; a < n; a++) {
            for (int b = a + 1; b < n; b++) {
                rSum += s[a][b] / (sd[a] * sd[b]);
            }
        }
        double rBar = 2 * rSum / ((double) n * (n - 1));
        double[][] f = new double[n][n];
        for (int a = 0; a < n; a++) {
            for (int b = 0; b < n; b++) {
                f[a][b] = a == b ? s[a][a] : rBar * sd[a] * sd[b];
            }
        }
        double pi = 0;
        double rhoDiag = 0;
        double rhoOff = 0;
        for (int a = 0; a < n; a++) {
            for (int b = 0; b < n; b++) {
                double piAb = 0;
                double thetaAa = 0;
                double thetaBb = 0;
                for (int i = 0; i < t; i++) {
                    double cross = x[i][a] * x[i][b] - s[a][b];
                    piAb += cross * cross;
                    if (a != b) {
                        thetaAa += (x[i][a] * x[i][a] - s[a][a]) * cross;
                        thetaBb += (x[i][b] * x[i][b] - s[b][b]) * cross;
                    }
                }
                piAb /= t;
                pi += piAb;
                if (a == b) {
                    rhoDiag += piAb;
                } else {
                    thetaAa /= t;
                    thetaBb /= t;
                    rhoOff += 0.5 * rBar * (sd[b] / sd[a] * thetaAa + sd[a] / sd[b] * thetaBb);
                }
            }
        }
        double rho = rhoDiag + rhoOff;
        double gamma = 0;
        for (int a = 0; a < n; a++) {
            for (int b = 0; b < n; b++) {
                double e = f[a][b] - s[a][b];
                gamma += e * e;
            }
        }
        double delta = gamma > 0 ? Math.max(0, Math.min(1, (pi - rho) / gamma / t)) : 1.0;
        double[][] shrunk = new double[n][n];
        for (int a = 0; a < n; a++) {
            for (int b = 0; b < n; b++) {
                shrunk[a][b] = delta * f[a][b] + (1 - delta) * s[a][b];
            }
        }
        return new Result(shrunk, delta, rBar);
    }

    private static double[][] demeaned(double[][] returns, int n) {
        int t = returns.length;
        double[] mean = new double[n];
        for (int i = 0; i < t; i++) {
            if (returns[i].length != n) {
                throw new IllegalArgumentException("ragged matrix: row " + i);
            }
            for (int j = 0; j < n; j++) {
                if (!Double.isFinite(returns[i][j])) {
                    throw new IllegalArgumentException("non-finite return at " + i + "," + j);
                }
                mean[j] += returns[i][j];
            }
        }
        for (int j = 0; j < n; j++) {
            mean[j] /= t;
        }
        double[][] x = new double[t][n];
        for (int i = 0; i < t; i++) {
            for (int j = 0; j < n; j++) {
                x[i][j] = returns[i][j] - mean[j];
            }
        }
        return x;
    }

    /** Convenience: the shrunk matrix only. */
    public static double[][] shrink(double[][] returns) {
        return ledoitWolf(returns).matrixCopy();
    }
}
