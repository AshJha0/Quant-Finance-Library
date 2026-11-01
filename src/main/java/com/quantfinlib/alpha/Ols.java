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

    public static final class Fit {
        private final double[] coefficients;
        private final double[] standardErrors;
        private final double[] hacStandardErrors;
        private final int hacLag;
        private final double[] residuals;
        private final double rSquared;

        /**
         * Creates the value; array and collection arguments are copied.
         *
         * @param coefficients fitted coefficients in design-matrix column order
         * @param standardErrors classical standard errors
         * @param hacStandardErrors Newey-West standard errors at {@code hacLag}
         * @param hacLag Bartlett lag used for the HAC errors
         * @param residuals {@code y − Xb}
         * @param rSquared {@code 1 − SSR/SST} (centred; 0 when y is constant)
         */
        public Fit(double[] coefficients, double[] standardErrors, double[] hacStandardErrors, int hacLag,
                double[] residuals, double rSquared) {
            this.coefficients = coefficients == null ? null : coefficients.clone();
            this.standardErrors = standardErrors == null ? null : standardErrors.clone();
            this.hacStandardErrors = hacStandardErrors == null ? null : hacStandardErrors.clone();
            this.hacLag = hacLag;
            this.residuals = residuals == null ? null : residuals.clone();
            this.rSquared = rSquared;
        }

        /** Element {@code i} of {@code coefficients} (fitted coefficients in design-matrix column order). */
        public double coefficients(int i) {
            return coefficients[i];
        }

        /** Length of {@code coefficients}. */
        public int coefficientsLength() {
            return coefficients.length;
        }

        /** A fresh copy of {@code coefficients}. */
        public double[] coefficientsCopy() {
            return coefficients == null ? null : coefficients.clone();
        }

        /** Element {@code i} of {@code standardErrors} (classical standard errors). */
        public double standardErrors(int i) {
            return standardErrors[i];
        }

        /** Length of {@code standardErrors}. */
        public int standardErrorsLength() {
            return standardErrors.length;
        }

        /** A fresh copy of {@code standardErrors}. */
        public double[] standardErrorsCopy() {
            return standardErrors == null ? null : standardErrors.clone();
        }

        /** Element {@code i} of {@code hacStandardErrors}. */
        public double hacStandardErrors(int i) {
            return hacStandardErrors[i];
        }

        /** Length of {@code hacStandardErrors}. */
        public int hacStandardErrorsLength() {
            return hacStandardErrors.length;
        }

        /** A fresh copy of {@code hacStandardErrors}. */
        public double[] hacStandardErrorsCopy() {
            return hacStandardErrors == null ? null : hacStandardErrors.clone();
        }

        /** {@code hacLag} (Bartlett lag used for the HAC errors). */
        public int hacLag() {
            return hacLag;
        }

        /** Element {@code i} of {@code residuals}. */
        public double residuals(int i) {
            return residuals[i];
        }

        /** Length of {@code residuals}. */
        public int residualsLength() {
            return residuals.length;
        }

        /** A fresh copy of {@code residuals}. */
        public double[] residualsCopy() {
            return residuals == null ? null : residuals.clone();
        }

        /** {@code rSquared}. */
        public double rSquared() {
            return rSquared;
        }

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

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Fit o)) {
                return false;
            }
            return java.util.Arrays.equals(coefficients, o.coefficients)
                    && java.util.Arrays.equals(standardErrors, o.standardErrors)
                    && java.util.Arrays.equals(hacStandardErrors, o.hacStandardErrors)
                    && hacLag == o.hacLag
                    && java.util.Arrays.equals(residuals, o.residuals)
                    && Double.compare(rSquared, o.rSquared) == 0;
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Arrays.hashCode(coefficients);
            h = 31 * h + java.util.Arrays.hashCode(standardErrors);
            h = 31 * h + java.util.Arrays.hashCode(hacStandardErrors);
            h = 31 * h + Integer.hashCode(hacLag);
            h = 31 * h + java.util.Arrays.hashCode(residuals);
            h = 31 * h + Double.hashCode(rSquared);
            return h;
        }

        @Override
        public String toString() {
            return "Fit[" + "coefficients=" + java.util.Arrays.toString(coefficients) + ", " + "standardErrors=" + java.util.Arrays.toString(standardErrors) + ", " + "hacStandardErrors=" + java.util.Arrays.toString(hacStandardErrors) + ", " + "hacLag=" + hacLag + ", " + "residuals=" + java.util.Arrays.toString(residuals) + ", " + "rSquared=" + rSquared + "]";
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
