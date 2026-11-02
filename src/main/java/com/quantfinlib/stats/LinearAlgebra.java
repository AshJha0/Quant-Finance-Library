package com.quantfinlib.stats;

/**
 * Dense linear algebra on {@code double[][]} row-major matrices: dot and
 * matrix-vector products, the quadratic form, Cholesky, a linear solve and
 * the inverse.
 *
 * <p>Added in 2.4.0 as the home of the matrix routines of
 * {@code util.MathUtils}, which is removed in 3.0. The code is moved, not
 * rewritten: results are bit-identical to the 2.3 {@code MathUtils}
 * methods (own test: {@code LinearAlgebraTest}).</p>
 *
 * @since 2.4.0
 */
public final class LinearAlgebra {

    private LinearAlgebra() {
    }


    /** Dot product; rejects arrays of different length. */
    public static double dot(double[] a, double[] b) {
        if (a.length != b.length) {
            // Localize the error here rather than truncating silently or
            // throwing AIOOBE three frames deep in a risk calculation.
            throw new IllegalArgumentException(
                    "length mismatch: " + a.length + " vs " + b.length);
        }
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }

    /** Matrix-vector product {@code m v}. */
    public static double[] matVec(double[][] m, double[] v) {
        double[] out = new double[m.length];
        for (int i = 0; i < m.length; i++) {
            out[i] = dot(m[i], v);
        }
        return out;
    }

    /** w' * M * w (quadratic form). */
    public static double quadraticForm(double[] w, double[][] m) {
        return dot(w, matVec(m, w));
    }

    /**
     * Cholesky decomposition: returns lower-triangular L with A = L * L'.
     * Adds tiny diagonal jitter if the matrix is borderline non-PSD
     * (rank-deficient factor models produce pivots a hair below zero),
     * but a pivot grossly negative relative to the diagonal scale means
     * the input is indefinite (a typo'd correlation &gt; 1, or
     * inconsistent pairwise estimates). Simulating a silently clamped,
     * different dependence structure would misstate risk, so that fails
     * loudly instead.
     */
    public static double[][] cholesky(double[][] a) {
        int n = a.length;
        double maxDiag = 0;
        for (int i = 0; i < n; i++) {
            maxDiag = Math.max(maxDiag, Math.abs(a[i][i]));
        }
        double indefinite = -1e-8 * Math.max(maxDiag, Double.MIN_NORMAL);
        double[][] l = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j <= i; j++) {
                double sum = a[i][j];
                for (int k = 0; k < j; k++) {
                    sum -= l[i][k] * l[j][k];
                }
                if (i == j) {
                    if (sum < indefinite) {
                        throw new IllegalArgumentException("matrix is not positive "
                                + "semi-definite (pivot " + i + " = " + sum
                                + ") — check for correlations beyond 1");
                    }
                    if (sum <= 0) {
                        sum = 1e-12;
                    }
                    l[i][i] = Math.sqrt(sum);
                } else {
                    l[i][j] = sum / l[j][j];
                }
            }
        }
        return l;
    }

    /**
     * Solves {@code A x = b} by Gaussian elimination with partial pivoting.
     * Inputs are not modified.
     */
    public static double[] solveLinear(double[][] a, double[] b) {
        int n = b.length;
        double[][] m = new double[n][];
        double[] rhs = b.clone();
        for (int i = 0; i < n; i++) {
            m[i] = a[i].clone();
        }
        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int r = col + 1; r < n; r++) {
                if (Math.abs(m[r][col]) > Math.abs(m[pivot][col])) {
                    pivot = r;
                }
            }
            if (Math.abs(m[pivot][col]) < 1e-12) {
                throw new IllegalArgumentException("singular system at column " + col);
            }
            double[] tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp;
            double tb = rhs[col]; rhs[col] = rhs[pivot]; rhs[pivot] = tb;
            for (int r = col + 1; r < n; r++) {
                double f = m[r][col] / m[col][col];
                rhs[r] -= f * rhs[col];
                for (int c = col; c < n; c++) {
                    m[r][c] -= f * m[col][c];
                }
            }
        }
        double[] x = new double[n];
        for (int i = n - 1; i >= 0; i--) {
            double s = rhs[i];
            for (int j = i + 1; j < n; j++) {
                s -= m[i][j] * x[j];
            }
            x[i] = s / m[i][i];
        }
        return x;
    }

    /** Matrix inverse by Gauss-Jordan elimination with partial pivoting. */
    public static double[][] inverse(double[][] a) {
        int n = a.length;
        double[][] aug = new double[n][2 * n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(a[i], 0, aug[i], 0, n);
            aug[i][n + i] = 1;
        }
        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int r = col + 1; r < n; r++) {
                if (Math.abs(aug[r][col]) > Math.abs(aug[pivot][col])) {
                    pivot = r;
                }
            }
            if (Math.abs(aug[pivot][col]) < 1e-12) {
                throw new IllegalArgumentException("singular matrix at column " + col);
            }
            double[] tmp = aug[col]; aug[col] = aug[pivot]; aug[pivot] = tmp;
            double diag = aug[col][col];
            for (int c = 0; c < 2 * n; c++) {
                aug[col][c] /= diag;
            }
            for (int r = 0; r < n; r++) {
                if (r == col) {
                    continue;
                }
                double f = aug[r][col];
                for (int c = 0; c < 2 * n; c++) {
                    aug[r][c] -= f * aug[col][c];
                }
            }
        }
        double[][] inv = new double[n][n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(aug[i], n, inv[i], 0, n);
        }
        return inv;
    }
}
