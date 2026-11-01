package com.quantfinlib.volatility;

/**
 * Ordinary least squares by Householder QR on column-scaled regressors, with
 * Newey-West (1987) heteroskedasticity- and autocorrelation-consistent
 * standard errors (Bartlett kernel). QR avoids forming {@code X'X}, whose
 * condition number is the square of that of {@code X}; scaling each column
 * to unit norm first removes the spread of units (an intercept of 1 beside
 * regressors of order 1e-4).
 */
final class LeastSquares {

    private LeastSquares() {
    }

    /**
     * @param beta          coefficients
     * @param hacStdErrors  Newey-West standard errors with {@code lags} lags
     * @param residualVariance  sum of squared residuals / (n − k)
     */
    record Result(double[] beta, double[] hacStdErrors, double residualVariance) {
    }

    /**
     * @param x    n×k design (rows are observations), n &gt; k
     * @param y    length n
     * @param lags Newey-West lag truncation L &ge; 0 (0 gives White SEs)
     */
    static Result fit(double[][] x, double[] y, int lags) {
        int n = x.length;
        int k = x[0].length;
        if (n <= k) {
            throw new IllegalArgumentException("need more observations than regressors");
        }
        double[] scale = new double[k];
        for (int j = 0; j < k; j++) {
            double s = 0;
            for (double[] row : x) {
                s += row[j] * row[j];
            }
            scale[j] = Math.sqrt(s);
            if (!(scale[j] > 0)) {
                throw new IllegalArgumentException("regressor " + j + " is identically zero");
            }
        }
        double[][] a = new double[n][k];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < k; j++) {
                a[i][j] = x[i][j] / scale[j];
            }
        }
        double[] b = y.clone();
        // Householder QR in place: A = Q R; apply Q' to b as we go.
        double[] rDiag = new double[k];
        for (int j = 0; j < k; j++) {
            double norm = 0;
            for (int i = j; i < n; i++) {
                norm = Math.hypot(norm, a[i][j]);
            }
            if (norm == 0) {
                throw new IllegalArgumentException("design matrix is rank deficient");
            }
            if (a[j][j] > 0) {
                norm = -norm;
            }
            for (int i = j; i < n; i++) {
                a[i][j] /= -norm;
            }
            a[j][j] += 1;
            for (int c = j + 1; c < k; c++) {
                double s = 0;
                for (int i = j; i < n; i++) {
                    s += a[i][j] * a[i][c];
                }
                s = -s / a[j][j];
                for (int i = j; i < n; i++) {
                    a[i][c] += s * a[i][j];
                }
            }
            double s = 0;
            for (int i = j; i < n; i++) {
                s += a[i][j] * b[i];
            }
            s = -s / a[j][j];
            for (int i = j; i < n; i++) {
                b[i] += s * a[i][j];
            }
            rDiag[j] = norm;
        }
        double maxR = 0;
        for (double d : rDiag) {
            maxR = Math.max(maxR, Math.abs(d));
        }
        for (double d : rDiag) {
            if (Math.abs(d) < 1e-12 * maxR) {
                throw new IllegalArgumentException("design matrix is numerically rank deficient");
            }
        }
        // Back-substitute R z = (Q'b)[0..k).
        double[] z = new double[k];
        for (int j = k - 1; j >= 0; j--) {
            double s = b[j];
            for (int c = j + 1; c < k; c++) {
                s -= a[j][c] * z[c];
            }
            z[j] = s / rDiag[j];
        }
        double[] beta = new double[k];
        for (int j = 0; j < k; j++) {
            beta[j] = z[j] / scale[j];
        }
        double[] e = new double[n];
        double ssr = 0;
        for (int i = 0; i < n; i++) {
            double fit = 0;
            for (int j = 0; j < k; j++) {
                fit += x[i][j] * beta[j];
            }
            e[i] = y[i] - fit;
            ssr += e[i] * e[i];
        }
        // HAC: (X'X)^{-1} S (X'X)^{-1}, S = Γ0 + Σ_l w_l (Γ_l + Γ_l'),
        // w_l = 1 − l/(L+1), Γ_l = Σ_t e_t e_{t−l} x_t x_{t−l}'. Computed in
        // the scaled coordinates, where X'X = R'R is well conditioned.
        double[][] xs = new double[n][k];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < k; j++) {
                xs[i][j] = x[i][j] / scale[j];
            }
        }
        double[][] xtx = new double[k][k];
        for (double[] row : xs) {
            for (int p = 0; p < k; p++) {
                for (int q = 0; q < k; q++) {
                    xtx[p][q] += row[p] * row[q];
                }
            }
        }
        double[][] inv = com.quantfinlib.util.MathUtils.inverse(xtx);
        double[][] s = new double[k][k];
        for (int l = 0; l <= lags; l++) {
            double w = l == 0 ? 1 : 1 - l / (lags + 1.0);
            for (int t = l; t < n; t++) {
                double ee = e[t] * e[t - l];
                for (int p = 0; p < k; p++) {
                    for (int q = 0; q < k; q++) {
                        double g = ee * xs[t][p] * xs[t - l][q];
                        s[p][q] += l == 0 ? g : w * g;
                        if (l > 0) {
                            s[q][p] += w * g;
                        }
                    }
                }
            }
        }
        double[] se = new double[k];
        for (int j = 0; j < k; j++) {
            double v = 0;
            for (int p = 0; p < k; p++) {
                for (int q = 0; q < k; q++) {
                    v += inv[j][p] * s[p][q] * inv[q][j];
                }
            }
            se[j] = Math.sqrt(Math.max(v, 0)) / scale[j];
        }
        return new Result(beta, se, ssr / (n - k));
    }
}
