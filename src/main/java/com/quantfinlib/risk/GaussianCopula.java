package com.quantfinlib.risk;

import com.quantfinlib.util.MathUtils;

import java.util.random.RandomGenerator;

/**
 * Gaussian and Student-t copula samplers — dependence separated from
 * marginals, which is the entire point of copula modeling: "these five
 * factors co-move like this" (the copula) is a different statement from
 * "each factor's own distribution looks like THAT" (the marginals), and
 * gluing arbitrary marginals to a chosen dependence structure is how
 * joint risk scenarios get built.
 *
 * <p>Both samplers emit correlated uniforms; feed them through your
 * marginals' inverse CDFs. The Gaussian copula has no tail dependence:
 * joint extremes are asymptotically independent, the property famously
 * blamed for pre-2008 CDO models. The Student-t copula with few degrees
 * of freedom has strong symmetric tail dependence (extremes cluster)
 * and converges to the Gaussian as {@code df → ∞}; the tests pin both
 * behaviors. Correlation matrices are factored at construction with
 * {@link PsdMatrices#choleskyPsd}: a positive semi-definite matrix
 * (including a rank-deficient one) is accepted, an indefinite one fails
 * here rather than as NaN samples later; repair an estimated matrix with
 * {@link PsdMatrices#nearestCorrelation} first. Deterministic per seed via
 * the caller-owned {@link RandomGenerator}.</p>
 */
public final class GaussianCopula {

    private final double[][] chol;         // lower-triangular Cholesky factor
    private final int dim;

    /** @param correlation symmetric positive semi-definite correlation matrix */
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
    public void sample(RandomGenerator rnd, double[] out, double[] scratch) {
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
     * shared shock is what creates tail dependence (one bad draw drags
     * every factor's tail together). Uniforms come from the exact t-CDF
     * ({@link MathUtils#tCdf}); a moment-matched normal approximation
     * here would distort exactly the tail quantiles this sampler exists
     * to model (at df = 3 it puts ~37% excess mass below the 1% level).
     */
    public void sampleT(RandomGenerator rnd, int df, double[] out, double[] scratch) {
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

    @SuppressWarnings("ReferenceEquality")   // identity is the property checked
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

    /** Cholesky factor of a PSD correlation matrix; see {@link PsdMatrices#choleskyPsd}. */
    static double[][] cholesky(double[][] matrix) {
        return PsdMatrices.choleskyPsd(matrix);
    }
}
