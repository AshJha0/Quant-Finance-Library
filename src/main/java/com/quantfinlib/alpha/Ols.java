package com.quantfinlib.alpha;

/**
 * Ordinary least squares by Householder QR, with classical and
 * Newey-West HAC standard errors.
 *
 * <p>QR rather than the normal equations: solving {@code XᵀX b = Xᵀy}
 * squares the condition number of {@code X}, which matters for factor
 * return streams that are 0.6 to 0.9 correlated. The coefficient
 * covariance uses {@code (XᵀX)⁻¹ = R⁻¹R⁻ᵀ} from the same factorisation.</p>
 *
 * <ul>
 *   <li>Classical: {@code V = σ̂² (XᵀX)⁻¹}, {@code σ̂² = e'e/(n − p)}.</li>
 *   <li>HAC (Newey and West 1987): {@code V = (XᵀX)⁻¹ S (XᵀX)⁻¹} with
 *       {@code S = Σ_t e_t² x_t x_tᵀ + Σ_{l=1..L} w_l Σ_t e_t e_{t−l}
 *       (x_t x_{t−l}ᵀ + x_{t−l} x_tᵀ)}, {@code w_l = 1 − l/(L+1)}. No
 *       small-sample degrees-of-freedom correction is applied.</li>
 * </ul>
 */
public final class Ols {

    /**
     * @param coefficients    fitted coefficients in design-matrix column order
     * @param standardErrors  classical standard errors
     * @param hacStandardErrors Newey-West standard errors at {@code hacLag}
     * @param hacLag          Bartlett lag used for the HAC errors
     * @param residuals       {@code y − Xb}
     * @param rSquared        {@code 1 − SSR/SST} (centred; 0 when y is constant)
     */
    public record Fit(double[] coefficients, double[] standardErrors, double[] hacStandardErrors,
                      int hacLag, double[] residuals, double rSquared) {

        /** Classical t-statistic of coefficient {@code j}. */
        public double tStat(int j) {
            return ratio(coefficients[j], standardErrors[j]);
        }

        /** HAC t-statistic of coefficient {@code j}. */
        public double hacTStat(int j) {
            return ratio(coefficients[j], hacStandardErrors[j]);
        }

        private static double ratio(double b, double se) {
            if (se > 0) {
                return b / se;
            }
            return b == 0 ? 0 : Math.signum(b) * Double.POSITIVE_INFINITY;
        }
    }

    private Ols() {
    }

    /**
     * Fits {@code y = X b + e}. The caller includes an intercept column if
     * one is wanted.
     *
     * @param x      design matrix, {@code x[t][j]}, n rows by p columns, n &gt; p
     * @param y      response, length n
     * @param hacLag Bartlett lag for the HAC errors (0 = White
     *               heteroskedasticity-robust errors)
     * @throws IllegalArgumentException on misaligned input, non-finite values
     *                                  or a rank-deficient design
     */
    public static Fit fit(double[][] x, double[] y, int hacLag) {
        int n = y.length;
        if (x.length != n || n == 0) {
            throw new IllegalArgumentException("x and y must have the same number of rows");
        }
        int p = x[0].length;
        if (p == 0 || n <= p) {
            throw new IllegalArgumentException("need more rows (" + n + ") than columns (" + p + ")");
        }
        if (hacLag < 0) {
            throw new IllegalArgumentException("hacLag must be >= 0");
        }
        // Column-major copy for Householder.
        double[][] a = new double[p][n];
        double[] qty = y.clone();
        for (int t = 0; t < n; t++) {
            if (x[t].length != p) {
                throw new IllegalArgumentException("row " + t + " has " + x[t].length + " columns");
            }
            if (!Double.isFinite(y[t])) {
                throw new IllegalArgumentException("non-finite y at row " + t);
            }
            for (int j = 0; j < p; j++) {
                if (!Double.isFinite(x[t][j])) {
                    throw new IllegalArgumentException("non-finite x at row " + t + " column " + j);
                }
                a[j][t] = x[t][j];
            }
        }
        double[] rDiag = new double[p];
        double scale = 0;
        for (int j = 0; j < p; j++) {
            for (int t = 0; t < n; t++) {
                scale = Math.max(scale, Math.abs(a[j][t]));
            }
        }
        for (int k = 0; k < p; k++) {
            double norm = 0;
            for (int t = k; t < n; t++) {
                norm = Math.hypot(norm, a[k][t]);
            }
            if (norm <= 1e-12 * Math.max(1, scale) * Math.sqrt(n)) {
                throw new IllegalArgumentException("design matrix is rank deficient at column " + k
                        + " (a constant or collinear regressor)");
            }
            if (a[k][k] < 0) {
                norm = -norm;
            }
            for (int t = k; t < n; t++) {
                a[k][t] /= norm;
            }
            a[k][k] += 1;
            for (int j = k + 1; j < p; j++) {
                double s = 0;
                for (int t = k; t < n; t++) {
                    s += a[k][t] * a[j][t];
                }
                s = -s / a[k][k];
                for (int t = k; t < n; t++) {
                    a[j][t] += s * a[k][t];
                }
            }
            double s = 0;
            for (int t = k; t < n; t++) {
                s += a[k][t] * qty[t];
            }
            s = -s / a[k][k];
            for (int t = k; t < n; t++) {
                qty[t] += s * a[k][t];
            }
            rDiag[k] = -norm;
        }
        // Back substitution R b = (Qᵀy)[0..p).
        double[] b = new double[p];
        for (int k = p - 1; k >= 0; k--) {
            double s = qty[k];
            for (int j = k + 1; j < p; j++) {
                s -= r(a, rDiag, k, j) * b[j];
            }
            b[k] = s / rDiag[k];
        }
        // (XᵀX)⁻¹ = R⁻¹ R⁻ᵀ.
        double[][] rInv = new double[p][p];
        for (int c = 0; c < p; c++) {
            for (int k = p - 1; k >= 0; k--) {
                double s = k == c ? 1 : 0;
                for (int j = k + 1; j < p; j++) {
                    s -= r(a, rDiag, k, j) * rInv[j][c];
                }
                rInv[k][c] = s / rDiag[k];
            }
        }
        double[][] xtxInv = new double[p][p];
        for (int i = 0; i < p; i++) {
            for (int j = 0; j < p; j++) {
                double s = 0;
                for (int k = 0; k < p; k++) {
                    s += rInv[i][k] * rInv[j][k];
                }
                xtxInv[i][j] = s;
            }
        }
        double[] e = new double[n];
        double ssr = 0;
        double meanY = 0;
        for (double v : y) {
            meanY += v;
        }
        meanY /= n;
        double sst = 0;
        for (int t = 0; t < n; t++) {
            double fit = 0;
            for (int j = 0; j < p; j++) {
                fit += x[t][j] * b[j];
            }
            e[t] = y[t] - fit;
            ssr += e[t] * e[t];
            sst += (y[t] - meanY) * (y[t] - meanY);
        }
        double sigma2 = ssr / (n - p);
        double[] se = new double[p];
        for (int j = 0; j < p; j++) {
            se[j] = Math.sqrt(Math.max(0, sigma2 * xtxInv[j][j]));
        }
        // Meat of the sandwich.
        int lag = Math.min(hacLag, n - 1);
        double[][] s = new double[p][p];
        for (int t = 0; t < n; t++) {
            for (int i = 0; i < p; i++) {
                for (int j = 0; j < p; j++) {
                    s[i][j] += e[t] * e[t] * x[t][i] * x[t][j];
                }
            }
        }
        for (int l = 1; l <= lag; l++) {
            double w = 1 - l / (lag + 1.0);
            for (int t = l; t < n; t++) {
                double ee = w * e[t] * e[t - l];
                for (int i = 0; i < p; i++) {
                    for (int j = 0; j < p; j++) {
                        s[i][j] += ee * (x[t][i] * x[t - l][j] + x[t - l][i] * x[t][j]);
                    }
                }
            }
        }
        double[] hac = new double[p];
        for (int j = 0; j < p; j++) {
            double v = 0;
            for (int i = 0; i < p; i++) {
                for (int k = 0; k < p; k++) {
                    v += xtxInv[j][i] * s[i][k] * xtxInv[k][j];
                }
            }
            hac[j] = Math.sqrt(Math.max(0, v));
        }
        return new Fit(b, se, hac, lag, e, sst == 0 ? 0 : 1 - ssr / sst);
    }

    /** Upper-triangular R entry (k, j), j &ge; k, from the Householder storage. */
    private static double r(double[][] a, double[] rDiag, int k, int j) {
        return j == k ? rDiag[k] : a[j][k];
    }
}
