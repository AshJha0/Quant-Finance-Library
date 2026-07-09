package com.quantfinlib.risk;

/**
 * Principal component analysis of a covariance matrix — the risk-factor
 * compressor. A yield curve has a dozen tenors but three real risk
 * factors (level, slope, curvature — the classic result this class
 * reproduces on curve data); an equity book has hundreds of names but a
 * handful of factors carrying most of the variance. PCA finds them:
 * the eigenvectors of the covariance matrix, ordered by how much
 * variance each explains.
 *
 * <p>Implementation is the cyclic Jacobi eigenvalue algorithm — exact
 * for symmetric matrices, dependency-free, and comfortably fast at risk
 * dimensions (tens of factors; it is O(n³) per sweep and converges in a
 * handful of sweeps). Eigenvalues clip at zero: a covariance matrix is
 * PSD in exact arithmetic, and a tiny negative eigenvalue is numerical
 * noise, not an imaginary risk factor. Research lane, deterministic.</p>
 */
public final class Pca {

    private final double[] eigenvalues;    // descending
    private final double[][] eigenvectors; // [component][factor], unit length
    private final double totalVariance;

    /**
     * Decomposes a symmetric covariance matrix (n×n, row-major square).
     * Asymmetry beyond floating-point noise is rejected — a typo'd
     * covariance must not silently symmetrize into a different matrix.
     */
    public Pca(double[][] covariance) {
        int n = covariance.length;
        if (n < 1) {
            throw new IllegalArgumentException("empty matrix");
        }
        double[][] m = new double[n][n];
        for (int i = 0; i < n; i++) {
            if (covariance[i].length != n) {
                throw new IllegalArgumentException("matrix must be square");
            }
            for (int j = 0; j < n; j++) {
                if (!Double.isFinite(covariance[i][j])) {
                    throw new IllegalArgumentException("non-finite covariance entry");
                }
                if (Math.abs(covariance[i][j] - covariance[j][i])
                        > 1e-9 * (1 + Math.abs(covariance[i][j]))) {
                    throw new IllegalArgumentException("matrix must be symmetric");
                }
                m[i][j] = covariance[i][j];
            }
        }
        // Normalize to unit scale before Jacobi: entries near 1e155 would
        // overflow the squared-norm accumulators to Infinity, and entries
        // near 1e-155 would underflow them to zero — either way corrupting
        // the convergence thresholds. Eigenvalues scale back linearly.
        double scale = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                scale = Math.max(scale, Math.abs(m[i][j]));
            }
        }
        if (scale > 0) {
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    m[i][j] /= scale;
                }
            }
        } else {
            scale = 1;                     // zero matrix: nothing to scale
        }
        double[][] v = identity(n);
        jacobi(m, v);

        // Extract diagonal eigenvalues + columns, sort descending.
        Integer[] order = new Integer[n];
        double[] eig = new double[n];
        for (int i = 0; i < n; i++) {
            eig[i] = m[i][i];
            order[i] = i;
        }
        java.util.Arrays.sort(order, (x, y) -> Double.compare(eig[y], eig[x]));
        eigenvalues = new double[n];
        eigenvectors = new double[n][n];
        double total = 0;
        for (int c = 0; c < n; c++) {
            int src = order[c];
            eigenvalues[c] = Math.max(0, eig[src]) * scale;  // PSD clip (see class doc)
            total += eigenvalues[c];
            for (int f = 0; f < n; f++) {
                eigenvectors[c][f] = v[f][src];
            }
        }
        totalVariance = total;
    }

    /** Variance carried by component {@code c} (descending order). */
    public double eigenvalue(int c) {
        return eigenvalues[c];
    }

    /** Unit loading of factor {@code f} on component {@code c}. */
    public double loading(int c, int f) {
        return eigenvectors[c][f];
    }

    /** Fraction of total variance the first {@code k} components explain. */
    public double explainedVariance(int k) {
        if (k < 1 || k > eigenvalues.length) {
            throw new IllegalArgumentException("k out of range");
        }
        if (totalVariance <= 0) {
            return 0;
        }
        double sum = 0;
        for (int c = 0; c < k; c++) {
            sum += eigenvalues[c];
        }
        return sum / totalVariance;
    }

    public int size() {
        return eigenvalues.length;
    }

    private static void jacobi(double[][] m, double[][] v) {
        int n = m.length;
        // Convergence must be RELATIVE to the matrix's own scale — the
        // caller chooses the units (return fractions vs currency², ~1e16
        // apart), so absolute thresholds either never trigger (burning
        // all sweeps on a converged matrix) or trigger too early.
        double normSq = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                normSq += m[i][j] * m[i][j];
            }
        }
        if (normSq == 0) {
            return;                        // the zero matrix is already diagonal
        }
        double offTol = 1e-24 * normSq;
        // The per-element skip threshold must be CONSISTENT with the total
        // off-diagonal stop: if every element sits below skipTol, their
        // summed squares must already satisfy off < offTol — otherwise all
        // rotations get skipped while the stop never fires, and the sweep
        // loop spins to the non-convergence throw on a converged matrix.
        double pairs = n > 1 ? n * (n - 1) / 2.0 : 1;
        double skipTol = Math.sqrt(offTol / pairs);
        for (int sweep = 0; sweep < 100; sweep++) {
            double off = 0;
            for (int p = 0; p < n; p++) {
                for (int q = p + 1; q < n; q++) {
                    off += m[p][q] * m[p][q];
                }
            }
            if (off < offTol) {
                return;
            }
            for (int p = 0; p < n; p++) {
                for (int q = p + 1; q < n; q++) {
                    if (Math.abs(m[p][q]) < skipTol) {
                        continue;
                    }
                    double theta = (m[q][q] - m[p][p]) / (2 * m[p][q]);
                    double t = Math.signum(theta) / (Math.abs(theta)
                            + Math.sqrt(theta * theta + 1));
                    if (theta == 0) {
                        t = 1;
                    }
                    double c = 1 / Math.sqrt(t * t + 1);
                    double s = t * c;
                    rotate(m, v, p, q, c, s, n);
                }
            }
        }
        // Jacobi converges quadratically — a symmetric matrix that has not
        // converged in 100 sweeps is a signal, not a diagonal to report.
        throw new IllegalStateException("Jacobi failed to converge in 100 sweeps");
    }

    private static void rotate(double[][] m, double[][] v, int p, int q,
                               double c, double s, int n) {
        for (int i = 0; i < n; i++) {
            double mip = m[i][p];
            double miq = m[i][q];
            m[i][p] = c * mip - s * miq;
            m[i][q] = s * mip + c * miq;
        }
        for (int i = 0; i < n; i++) {
            double mpi = m[p][i];
            double mqi = m[q][i];
            m[p][i] = c * mpi - s * mqi;
            m[q][i] = s * mpi + c * mqi;
            double vip = v[i][p];
            double viq = v[i][q];
            v[i][p] = c * vip - s * viq;
            v[i][q] = s * vip + c * viq;
        }
    }

    private static double[][] identity(int n) {
        double[][] id = new double[n][n];
        for (int i = 0; i < n; i++) {
            id[i][i] = 1;
        }
        return id;
    }
}
