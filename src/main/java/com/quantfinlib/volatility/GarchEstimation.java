package com.quantfinlib.volatility;

import com.quantfinlib.optimization.NelderMead;
import com.quantfinlib.util.MathUtils;

/**
 * Shared maximum-likelihood machinery for the GARCH family.
 *
 * <p>Each model maps an unconstrained vector {@code u} onto its admissible
 * parameters {@code θ}; the negative log-likelihood is minimised over
 * {@code u} by {@link NelderMead} from several starting points. Standard
 * errors come from the observed information in {@code θ} (central second
 * differences of the total log-likelihood), and robust (QMLE) standard
 * errors from the Bollerslev-Wooldridge (1992) sandwich
 * {@code H⁻¹·J·H⁻¹}, J the outer product of per-observation scores.
 * When the information matrix is not positive definite (an estimate on
 * the boundary, e.g. α = 0) the standard errors are reported as NaN; that
 * is the one documented NaN in the family.</p>
 */
final class GarchEstimation {

    private GarchEstimation() {
    }

    /** One GARCH-family model. */
    interface Model {
        /** Number of parameters, including ν for Student-t. */
        int size();

        /** Admissible parameters from an unconstrained vector. */
        double[] toTheta(double[] u);

        /** Inverse of {@link #toTheta} for an admissible θ. */
        double[] toU(double[] theta);

        /**
         * Log-likelihood of demeaned returns {@code r}; writes per-observation
         * terms into {@code perObs} when it is not null. Returns
         * {@code -Infinity} for parameters the recursion cannot evaluate.
         */
        double logLik(double[] theta, double[] r, double[] perObs);
    }

    static final class Estimate {
        private final double[] theta;
        private final double logLikelihood;
        private final double[] se;
        private final double[] robustSe;

        /**
         * Creates the value; array and collection arguments are copied.
         */
        Estimate(double[] theta, double logLikelihood, double[] se, double[] robustSe) {
            this.theta = theta;
            this.logLikelihood = logLikelihood;
            this.se = se;
            this.robustSe = robustSe;
        }

        /** {@code theta}. */
        double[] theta() {
            return theta;
        }

        /** {@code logLikelihood}. */
        double logLikelihood() {
            return logLikelihood;
        }

        /** {@code se}. */
        double[] se() {
            return se;
        }

        /** {@code robustSe}. */
        double[] robustSe() {
            return robustSe;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Estimate o)) {
                return false;
            }
            return java.util.Arrays.equals(theta, o.theta)
                    && Double.compare(logLikelihood, o.logLikelihood) == 0
                    && java.util.Arrays.equals(se, o.se)
                    && java.util.Arrays.equals(robustSe, o.robustSe);
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Arrays.hashCode(theta);
            h = 31 * h + Double.hashCode(logLikelihood);
            h = 31 * h + java.util.Arrays.hashCode(se);
            h = 31 * h + java.util.Arrays.hashCode(robustSe);
            return h;
        }

        @Override
        public String toString() {
            return "Estimate[" + "theta=" + java.util.Arrays.toString(theta) + ", " + "logLikelihood=" + logLikelihood + ", " + "se=" + java.util.Arrays.toString(se) + ", " + "robustSe=" + java.util.Arrays.toString(robustSe) + "]";
        }
    }

    static Estimate estimate(Model model, double[] r, double[][] startThetas) {
        double[] bestU = null;
        double bestF = Double.POSITIVE_INFINITY;
        for (double[] start : startThetas) {
            double[] u0 = model.toU(start);
            double f0 = -model.logLik(model.toTheta(u0), r, null);
            if (!Double.isFinite(f0)) {
                continue;
            }
            NelderMead.Result res = NelderMead.minimize(
                    u -> -model.logLik(model.toTheta(u), r, null), u0, 0.5, 1e-12, 200_000);
            if (res.value() < bestF) {
                bestF = res.value();
                bestU = res.xCopy();
            }
        }
        if (bestU == null) {
            throw new IllegalArgumentException("likelihood is not finite at any starting point");
        }
        double[] theta = model.toTheta(bestU);
        double ll = model.logLik(theta, r, null);
        double[][] cov = covariances(model, theta, r);
        return new Estimate(theta, ll, cov[0], cov[1]);
    }

    /** {standard errors, robust standard errors} at θ. */
    private static double[][] covariances(Model model, double[] theta, double[] r) {
        int k = theta.length;
        int n = r.length;
        double[] h = new double[k];
        for (int i = 0; i < k; i++) {
            h[i] = 1e-4 * Math.max(Math.abs(theta[i]), 1e-4);
        }
        double f0 = model.logLik(theta, r, null);
        double[][] hess = new double[k][k];
        for (int i = 0; i < k; i++) {
            for (int j = i; j < k; j++) {
                double v;
                if (i == j) {
                    double fp = model.logLik(shift(theta, i, h[i], -1, 0), r, null);
                    double fm = model.logLik(shift(theta, i, -h[i], -1, 0), r, null);
                    v = (fp - 2 * f0 + fm) / (h[i] * h[i]);
                } else {
                    double fpp = model.logLik(shift(theta, i, h[i], j, h[j]), r, null);
                    double fpm = model.logLik(shift(theta, i, h[i], j, -h[j]), r, null);
                    double fmp = model.logLik(shift(theta, i, -h[i], j, h[j]), r, null);
                    double fmm = model.logLik(shift(theta, i, -h[i], j, -h[j]), r, null);
                    v = (fpp - fpm - fmp + fmm) / (4 * h[i] * h[j]);
                }
                hess[i][j] = v;
                hess[j][i] = v;
            }
        }
        double[][] scores = new double[k][n];
        double[] plus = new double[n];
        double[] minus = new double[n];
        for (int i = 0; i < k; i++) {
            model.logLik(shift(theta, i, h[i], -1, 0), r, plus);
            model.logLik(shift(theta, i, -h[i], -1, 0), r, minus);
            for (int t = 0; t < n; t++) {
                scores[i][t] = (plus[t] - minus[t]) / (2 * h[i]);
            }
        }
        double[] se = new double[k];
        double[] robust = new double[k];
        java.util.Arrays.fill(se, Double.NaN);
        java.util.Arrays.fill(robust, Double.NaN);
        double[][] info = new double[k][k];
        boolean finite = true;
        for (int i = 0; i < k; i++) {
            for (int j = 0; j < k; j++) {
                info[i][j] = -hess[i][j];
                finite &= Double.isFinite(info[i][j]);
            }
        }
        if (!finite) {
            return new double[][]{se, robust};
        }
        double[][] inv;
        try {
            com.quantfinlib.risk.PsdMatrices.choleskyPsd(info);
            inv = MathUtils.inverse(info);
        } catch (RuntimeException notPd) {
            return new double[][]{se, robust};
        }
        double[][] j = new double[k][k];
        for (int a = 0; a < k; a++) {
            for (int b = 0; b < k; b++) {
                double s = 0;
                for (int t = 0; t < n; t++) {
                    s += scores[a][t] * scores[b][t];
                }
                j[a][b] = s;
            }
        }
        for (int a = 0; a < k; a++) {
            if (inv[a][a] > 0) {
                se[a] = Math.sqrt(inv[a][a]);
            }
            double s = 0;
            for (int p = 0; p < k; p++) {
                for (int q = 0; q < k; q++) {
                    s += inv[a][p] * j[p][q] * inv[q][a];
                }
            }
            if (s > 0) {
                robust[a] = Math.sqrt(s);
            }
        }
        return new double[][]{se, robust};
    }

    private static double[] shift(double[] theta, int i, double di, int j, double dj) {
        double[] t = theta.clone();
        t[i] += di;
        if (j >= 0) {
            t[j] += dj;
        }
        return t;
    }

    // ------------------------------------------------------------------
    // Shared pieces
    // ------------------------------------------------------------------

    static double[] demean(double[] returns) {
        if (returns.length < 100) {
            throw new IllegalArgumentException("need at least 100 returns, got " + returns.length);
        }
        for (double x : returns) {
            if (!Double.isFinite(x)) {
                throw new IllegalArgumentException("returns must be finite");
            }
        }
        double mean = MathUtils.mean(returns);
        double[] r = new double[returns.length];
        for (int i = 0; i < r.length; i++) {
            r[i] = returns[i] - mean;
        }
        if (!(MathUtils.variance(r) > 0)) {
            throw new IllegalArgumentException("returns carry no variance");
        }
        return r;
    }

    static double logistic(double u) {
        return 1 / (1 + Math.exp(-u));
    }

    static double logit(double p) {
        return Math.log(p / (1 - p));
    }

    /** Lower bound on ν so the standardised t has finite variance with margin. */
    static final double NU_MIN = 2.05;

    static double nuFromU(double u) {
        return NU_MIN + Math.exp(u);
    }

    static double uFromNu(double nu) {
        return Math.log(nu - NU_MIN);
    }

    /**
     * Log density of one standardised innovation: Gaussian when
     * {@code nu} is NaN, otherwise the unit-variance Student-t with
     * {@code nu} degrees of freedom (Bollerslev 1987):
     * {@code lnΓ((ν+1)/2) − lnΓ(ν/2) − ½ln(π(ν−2)) − ½ln h − (ν+1)/2·ln(1 + x²/(h(ν−2)))}.
     */
    static double logDensity(double x, double h, double nu, double tConst) {
        if (Double.isNaN(nu)) {
            return -0.5 * (LN_2PI + Math.log(h) + x * x / h);
        }
        return tConst - 0.5 * Math.log(h) - 0.5 * (nu + 1) * Math.log1p(x * x / (h * (nu - 2)));
    }

    static double tConst(double nu) {
        if (Double.isNaN(nu)) {
            return 0;
        }
        return MathUtils.logGamma(0.5 * (nu + 1)) - MathUtils.logGamma(0.5 * nu)
                - 0.5 * Math.log(Math.PI * (nu - 2));
    }

    private static final double LN_2PI = Math.log(2 * Math.PI);
}
