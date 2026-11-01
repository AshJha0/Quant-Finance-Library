package com.quantfinlib.risk;

/**
 * Factorisation and repair of symmetric positive semi-definite matrices:
 * the one place in the risk, simulation and copula code that decides what a
 * "valid covariance" is.
 *
 * <ul>
 *   <li>{@link #choleskyPsd} factors {@code A = L·L'} for a positive
 *       semi-definite {@code A}. A pivot that is zero to rounding (a
 *       rank-deficient factor covariance, two identical assets) gives a zero
 *       column, so the factor still reproduces {@code A}. A pivot that is
 *       clearly negative, or a zero pivot whose column is not zero, means the
 *       matrix is indefinite and the method throws. Tolerances are relative
 *       to the largest diagonal entry, so the result does not depend on
 *       units.</li>
 *   <li>{@link #symmetricEigen} is the cyclic Jacobi eigen-decomposition
 *       (Golub and Van Loan, 4th ed., Algorithm 8.5.3). It is O(n³) per sweep
 *       and suited to a few hundred dimensions at most.</li>
 *   <li>{@link #nearestPsd} clips negative eigenvalues to zero, which is the
 *       Frobenius-nearest PSD matrix (Higham 1988).</li>
 *   <li>{@link #nearestCorrelation} is Higham's (2002) alternating
 *       projections method with Dykstra's correction, unit weights: the
 *       Frobenius-nearest matrix that is PSD with unit diagonal.</li>
 * </ul>
 *
 * <p>References: N. J. Higham, "Computing the nearest correlation matrix -
 * a problem from finance", IMA J. Numer. Anal. 22 (2002) 329-343; N. J.
 * Higham, "Computing a nearest symmetric positive semidefinite matrix",
 * Linear Algebra Appl. 103 (1988) 103-118.</p>
 */
public final class PsdMatrices {

    /** A clearly negative pivot, relative to the largest diagonal entry. */
    private static final double INDEFINITE = 1e-8;
    /** A pivot treated as zero, relative to the largest diagonal entry. */
    private static final double ZERO_PIVOT = 1e-12;

    private PsdMatrices() {
    }

    /** Eigenvalues (descending) and matching unit eigenvectors {@code vectors[k]}. */
    public record Eigen(double[] values, double[][] vectors) {
    }

    /**
     * Lower-triangular {@code L} with {@code A = L·L'} for a symmetric PSD
     * matrix. See the class comment for how semi-definite and indefinite
     * input are told apart.
     *
     * @throws IllegalArgumentException if {@code a} is not square, not
     *                                  symmetric, not finite, or indefinite
     */
    public static double[][] choleskyPsd(double[][] a) {
        int n = checkSymmetric(a);
        double maxDiag = 0;
        for (int i = 0; i < n; i++) {
            maxDiag = Math.max(maxDiag, Math.abs(a[i][i]));
        }
        double scale = Math.max(maxDiag, Double.MIN_NORMAL);
        double zeroPivot = ZERO_PIVOT * scale;
        double indefinite = -INDEFINITE * scale;
        double[][] l = new double[n][n];
        for (int j = 0; j < n; j++) {
            double d = a[j][j];
            for (int k = 0; k < j; k++) {
                d -= l[j][k] * l[j][k];
            }
            if (d < indefinite) {
                throw new IllegalArgumentException("matrix is not positive semi-definite (pivot "
                        + j + " = " + d + "); repair it with nearestPsd or nearestCorrelation");
            }
            boolean zero = d <= zeroPivot;
            double ljj = zero ? 0 : Math.sqrt(d);
            l[j][j] = ljj;
            for (int i = j + 1; i < n; i++) {
                double s = a[i][j];
                for (int k = 0; k < j; k++) {
                    s -= l[i][k] * l[j][k];
                }
                if (zero) {
                    // A PSD matrix with a zero pivot has a zero residual column.
                    double bound = 1e-6 * Math.sqrt(scale * Math.max(Math.abs(a[i][i]), zeroPivot));
                    if (Math.abs(s) > bound) {
                        throw new IllegalArgumentException("matrix is not positive semi-definite"
                                + " (zero pivot " + j + " with residual " + s + " in row " + i + ")");
                    }
                    l[i][j] = 0;
                } else {
                    l[i][j] = s / ljj;
                }
            }
        }
        return l;
    }

    /** Cyclic Jacobi eigen-decomposition of a symmetric matrix. */
    public static Eigen symmetricEigen(double[][] a) {
        int n = checkSymmetric(a);
        double[][] m = new double[n][n];
        double scale = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                m[i][j] = 0.5 * (a[i][j] + a[j][i]);
                scale = Math.max(scale, Math.abs(m[i][j]));
            }
        }
        double[][] v = new double[n][n];
        for (int i = 0; i < n; i++) {
            v[i][i] = 1;
        }
        if (scale > 0) {
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    m[i][j] /= scale;
                }
            }
            jacobi(m, v);
        } else {
            scale = 1;
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        final double[][] mm = m;
        java.util.Arrays.sort(order, (x, y) -> Double.compare(mm[y][y], mm[x][x]));
        double[] values = new double[n];
        double[][] vectors = new double[n][n];
        for (int k = 0; k < n; k++) {
            int src = order[k];
            values[k] = m[src][src] * scale;
            for (int f = 0; f < n; f++) {
                vectors[k][f] = v[f][src];
            }
        }
        return new Eigen(values, vectors);
    }

    /** Frobenius-nearest PSD matrix: eigenvalues below zero set to zero. */
    public static double[][] nearestPsd(double[][] a) {
        return projectPsd(a);
    }

    /** Nearest correlation matrix with tolerance 1e-12 and at most 10 000 iterations. */
    public static double[][] nearestCorrelation(double[][] a) {
        return nearestCorrelation(a, 1e-12, 10_000);
    }

    /**
     * Higham (2002) alternating projections with Dykstra's correction:
     * {@code R = Y − ΔS; X = P_S(R); ΔS = X − R; Y = P_U(X)}, where
     * {@code P_S} clips eigenvalues at zero and {@code P_U} sets the
     * diagonal to one. Stops when {@code ||Y − X||_F <= tol·||Y||_F}.
     *
     * @throws IllegalStateException if it has not converged in {@code maxIter}
     */
    public static double[][] nearestCorrelation(double[][] a, double tol, int maxIter) {
        int n = checkSymmetric(a);
        if (!(tol > 0) || maxIter < 1) {
            throw new IllegalArgumentException("tol must be > 0 and maxIter >= 1");
        }
        double[][] y = copy(a);
        double[][] ds = new double[n][n];
        for (int it = 0; it < maxIter; it++) {
            double[][] r = new double[n][n];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    r[i][j] = y[i][j] - ds[i][j];
                }
            }
            double[][] x = projectPsd(r);
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    ds[i][j] = x[i][j] - r[i][j];
                }
            }
            double diff = 0;
            double norm = 0;
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    double yij = i == j ? 1 : x[i][j];
                    double e = yij - x[i][j];
                    diff += e * e;
                    norm += yij * yij;
                    y[i][j] = yij;
                }
            }
            if (Math.sqrt(diff) <= tol * Math.sqrt(norm)) {
                return y;
            }
        }
        throw new IllegalStateException("nearestCorrelation did not converge in " + maxIter
                + " iterations");
    }

    // ------------------------------------------------------------------

    private static double[][] projectPsd(double[][] a) {
        int n = a.length;
        Eigen e = symmetricEigen(a);
        double[][] out = new double[n][n];
        for (int k = 0; k < n; k++) {
            double lam = e.values()[k];
            if (lam <= 0) {
                continue;
            }
            double[] vk = e.vectors()[k];
            for (int i = 0; i < n; i++) {
                double vi = lam * vk[i];
                for (int j = 0; j < n; j++) {
                    out[i][j] += vi * vk[j];
                }
            }
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double s = 0.5 * (out[i][j] + out[j][i]);
                out[i][j] = s;
                out[j][i] = s;
            }
        }
        return out;
    }

    private static void jacobi(double[][] m, double[][] v) {
        int n = m.length;
        double normSq = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                normSq += m[i][j] * m[i][j];
            }
        }
        double offTol = 1e-28 * normSq;
        for (int sweep = 0; sweep < 100; sweep++) {
            double off = 0;
            for (int p = 0; p < n; p++) {
                for (int q = p + 1; q < n; q++) {
                    off += m[p][q] * m[p][q];
                }
            }
            if (off <= offTol) {
                return;
            }
            for (int p = 0; p < n; p++) {
                for (int q = p + 1; q < n; q++) {
                    double apq = m[p][q];
                    if (apq == 0) {
                        continue;
                    }
                    double theta = (m[q][q] - m[p][p]) / (2 * apq);
                    double t = theta == 0 ? 1
                            : Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
                    double c = 1 / Math.sqrt(t * t + 1);
                    double s = t * c;
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
            }
        }
        throw new IllegalStateException("Jacobi did not converge in 100 sweeps");
    }

    static int checkSymmetric(double[][] a) {
        int n = a.length;
        if (n < 1) {
            throw new IllegalArgumentException("empty matrix");
        }
        double scale = 0;
        for (int i = 0; i < n; i++) {
            if (a[i].length != n) {
                throw new IllegalArgumentException("matrix must be square");
            }
            for (int j = 0; j < n; j++) {
                if (!Double.isFinite(a[i][j])) {
                    throw new IllegalArgumentException("non-finite entry at " + i + "," + j);
                }
                scale = Math.max(scale, Math.abs(a[i][j]));
            }
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (Math.abs(a[i][j] - a[j][i]) > 1e-10 * Math.max(scale, Double.MIN_NORMAL)) {
                    throw new IllegalArgumentException("matrix must be symmetric at " + i + "," + j);
                }
            }
        }
        return n;
    }

    private static double[][] copy(double[][] a) {
        double[][] c = new double[a.length][];
        for (int i = 0; i < a.length; i++) {
            c[i] = a[i].clone();
        }
        return c;
    }
}
