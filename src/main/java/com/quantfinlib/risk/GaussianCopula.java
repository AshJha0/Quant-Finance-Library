package com.quantfinlib.risk;

import com.quantfinlib.util.MathUtils;

import java.util.Random;

/**
 * Gaussian and Student-t copula samplers — dependence separated from
 * marginals, which is the entire point of copula modeling: "these five
 * factors co-move like THIS" (the copula) is a different statement from
 * "each factor's own distribution looks like THAT" (the marginals), and
 * gluing arbitrary marginals to a chosen dependence structure is how
 * joint risk scenarios get built.
 *
 * <p>Both samplers emit correlated UNIFORMS — feed them through your
 * marginals' inverse CDFs. The Gaussian copula has NO tail dependence:
 * joint extremes are asymptotically independent, the property famously
 * blamed for pre-2008 CDO models. The Student-t copula with few degrees
 * of freedom has strong SYMMETRIC tail dependence — extremes cluster —
 * and converges to the Gaussian as {@code df → ∞}; the tests pin both
 * behaviors. Correlation matrices are Cholesky-factored at construction
 * (must be positive-definite — a borderline matrix fails loudly here,
 * not as NaN samples later). Research lane; deterministic per seed via
 * the caller-owned {@link Random}.</p>
 */
public final class GaussianCopula {

    private final double[][] chol;         // lower-triangular Cholesky factor
    private final int dim;

    /** @param correlation symmetric positive-definite correlation matrix */
    public GaussianCopula(double[][] correlation) {
        this.dim = correlation.length;
        this.chol = cholesky(correlation);
    }

    /**
     * One Gaussian-copula draw: {@code out[i]} are correlated uniforms
     * in (0, 1). Arrays are caller-owned; {@code scratch} must be a
     * second array of the same length (kept separate so the sampler
     * allocates nothing per draw).
     */
    public void sample(Random rnd, double[] out, double[] scratch) {
        requireDistinct(out, scratch);
        for (int i = 0; i < dim; i++) {
            scratch[i] = rnd.nextGaussian();
        }
        correlate(scratch, out);
        for (int i = 0; i < dim; i++) {
            out[i] = MathUtils.normCdf(out[i]);
        }
    }

    /**
     * One Student-t-copula draw with {@code df} degrees of freedom:
     * the same correlated Gaussians divided by a shared √(χ²/df) — the
     * SHARED shock is what creates tail dependence (one bad draw drags
     * every factor's tail together). Uniforms come from the EXACT t-CDF
     * ({@link MathUtils#tCdf}); a moment-matched normal approximation
     * here would distort exactly the tail quantiles this sampler exists
     * to model (at df = 3 it puts ~37% excess mass below the 1% level).
     */
    public void sampleT(Random rnd, int df, double[] out, double[] scratch) {
        if (df < 1) {
            throw new IllegalArgumentException("df must be >= 1");
        }
        requireDistinct(out, scratch);
        for (int i = 0; i < dim; i++) {
            scratch[i] = rnd.nextGaussian();
        }
        correlate(scratch, out);
        double chiSq = 0;
        for (int k = 0; k < df; k++) {
            double z = rnd.nextGaussian();
            chiSq += z * z;
        }
        double scale = Math.sqrt(df / Math.max(chiSq, 1e-12));
        for (int i = 0; i < dim; i++) {
            out[i] = MathUtils.tCdf(out[i] * scale, df);
        }
    }

    public int dimension() {
        return dim;
    }

    private void correlate(double[] z, double[] out) {
        for (int i = 0; i < dim; i++) {
            double sum = 0;
            for (int j = 0; j <= i; j++) {
                sum += chol[i][j] * z[j];
            }
            out[i] = sum;
        }
    }

    private void requireDistinct(double[] out, double[] scratch) {
        requireLength(out);
        requireLength(scratch);
        // correlate() reads scratch while writing out — aliasing them
        // silently corrupts the dependence structure from dim 3 up.
        if (out == scratch) {
            throw new IllegalArgumentException("out and scratch must be distinct arrays");
        }
    }

    private void requireLength(double[] a) {
        if (a.length < dim) {
            throw new IllegalArgumentException(
                    "array has " + a.length + " entries, copula has " + dim);
        }
    }

    /** Cholesky factorization; fails loudly on a non-positive-definite input. */
    static double[][] cholesky(double[][] matrix) {
        int n = matrix.length;
        // The pivot tolerance is RELATIVE to the matrix's own diagonal
        // scale: correlation matrices (diag 1) factor exactly as before,
        // and genuinely positive-definite covariances quoted in small
        // units are no longer rejected (a 0.5bp-daily-vol factor has
        // variance ~2.5e-9; an absolute 1e-12 floor called a valid pair
        // of them "not positive-definite").
        double maxDiag = 0;
        for (int i = 0; i < n; i++) {
            if (matrix[i].length != n) {
                throw new IllegalArgumentException("matrix must be square");
            }
            maxDiag = Math.max(maxDiag, Math.abs(matrix[i][i]));
        }
        double pivotFloor = 1e-12 * Math.max(maxDiag, Double.MIN_NORMAL);
        double[][] l = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j <= i; j++) {
                double sum = matrix[i][j];
                for (int k = 0; k < j; k++) {
                    sum -= l[i][k] * l[j][k];
                }
                if (i == j) {
                    if (!(sum > pivotFloor)) {
                        throw new IllegalArgumentException(
                                "matrix is not positive-definite (pivot " + i + " = " + sum + ")");
                    }
                    l[i][j] = Math.sqrt(sum);
                } else {
                    l[i][j] = sum / l[j][j];
                }
            }
        }
        return l;
    }
}
