package com.quantfinlib.volatility;

import com.quantfinlib.util.MathUtils;

/**
 * GARCH(1,1) volatility model (Bollerslev 1986, J. Econometrics 31, 307-327):
 * {@code h_t = ω + α r_{t-1}² + β h_{t-1}} on demeaned returns.
 *
 * <p>Estimation is full maximum likelihood over (ω, α, β) and, for
 * {@link Innovation#STUDENT_T}, the degrees of freedom ν. The admissible
 * region ω &gt; 0, α, β &ge; 0, α + β &lt; 1 is mapped onto R³ by
 * {@code ω = e^{u₀}}, {@code α = p·s}, {@code β = p·(1−s)} with
 * {@code p = logistic(u₁)}, {@code s = logistic(u₂)}, and the negative
 * log-likelihood is minimised by Nelder-Mead from three starting points.
 * The recursion starts at {@code h₀} = sample variance of the demeaned
 * returns (the usual backcast). Standard errors come from the observed
 * information (numerical Hessian); robust QMLE standard errors from the
 * Bollerslev-Wooldridge (1992) sandwich, which stay valid when the
 * innovation is not Gaussian. Both are NaN when the estimate sits on the
 * boundary and the information matrix is singular.</p>
 */
public final class Garch11 {

    public record Params(double omega, double alpha, double beta, double logLikelihood) {

        public double persistence() {
            return alpha + beta;
        }

        public double unconditionalVariance() {
            return omega / (1 - alpha - beta);
        }
    }

    /**
     * A fitted model with its uncertainty.
     */
    public static final class Fit {
        private final Params params;
        private final Innovation innovation;
        private final double nu;
        private final double[] standardErrors;
        private final double[] robustStandardErrors;

        /**
         * Creates the value; array and collection arguments are copied.
         *
         * @param params ω, α, β and the maximised log-likelihood
         * @param innovation the innovation distribution used
         * @param nu Student-t degrees of freedom, NaN for Gaussian
         * @param standardErrors SEs of (ω, α, β[, ν]) from the observed information
         * @param robustStandardErrors Bollerslev-Wooldridge QMLE SEs, same order
         */
        public Fit(Params params, Innovation innovation, double nu, double[] standardErrors,
                double[] robustStandardErrors) {
            this.params = params;
            this.innovation = innovation;
            this.nu = nu;
            this.standardErrors = standardErrors == null ? null : standardErrors.clone();
            this.robustStandardErrors = robustStandardErrors == null ? null : robustStandardErrors.clone();
        }

        /** {@code params} (ω, α, β and the maximised log-likelihood). */
        public Params params() {
            return params;
        }

        /** {@code innovation} (the innovation distribution used). */
        public Innovation innovation() {
            return innovation;
        }

        /** {@code nu} (Student-t degrees of freedom, NaN for Gaussian). */
        public double nu() {
            return nu;
        }

        /** Element {@code i} of {@code standardErrors} (SEs of (ω, α, β[, ν]) from the observed information). */
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

        /** Element {@code i} of {@code robustStandardErrors} (Bollerslev-Wooldridge QMLE SEs, same order). */
        public double robustStandardErrors(int i) {
            return robustStandardErrors[i];
        }

        /** Length of {@code robustStandardErrors}. */
        public int robustStandardErrorsLength() {
            return robustStandardErrors.length;
        }

        /** A fresh copy of {@code robustStandardErrors}. */
        public double[] robustStandardErrorsCopy() {
            return robustStandardErrors == null ? null : robustStandardErrors.clone();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Fit o)) {
                return false;
            }
            return java.util.Objects.equals(params, o.params)
                    && java.util.Objects.equals(innovation, o.innovation)
                    && Double.compare(nu, o.nu) == 0
                    && java.util.Arrays.equals(standardErrors, o.standardErrors)
                    && java.util.Arrays.equals(robustStandardErrors, o.robustStandardErrors);
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Objects.hashCode(params);
            h = 31 * h + java.util.Objects.hashCode(innovation);
            h = 31 * h + Double.hashCode(nu);
            h = 31 * h + java.util.Arrays.hashCode(standardErrors);
            h = 31 * h + java.util.Arrays.hashCode(robustStandardErrors);
            return h;
        }

        @Override
        public String toString() {
            return "Fit[" + "params=" + params + ", " + "innovation=" + innovation + ", " + "nu=" + nu + ", " + "standardErrors=" + java.util.Arrays.toString(standardErrors) + ", " + "robustStandardErrors=" + java.util.Arrays.toString(robustStandardErrors) + "]";
        }
    }

    private Garch11() {
    }

    /** Gaussian maximum-likelihood fit; see {@link #estimate}. */
    public static Params fit(double[] returns) {
        return estimate(returns, Innovation.GAUSSIAN).params();
    }

    /**
     * Maximum-likelihood fit with standard errors.
     *
     * @param returns at least 100 finite returns; the sample mean is removed
     */
    public static Fit estimate(double[] returns, Innovation innovation) {
        double[] r = GarchEstimation.demean(returns);
        double var = MathUtils.variance(r);
        boolean t = innovation == Innovation.STUDENT_T;
        Model model = new Model(var, t);
        double[][] starts = {
                start(var, 0.05, 0.90, t), start(var, 0.10, 0.60, t), start(var, 0.03, 0.96, t)};
        GarchEstimation.Estimate e = GarchEstimation.estimate(model, r, starts);
        double[] th = e.theta();
        return new Fit(new Params(th[0], th[1], th[2], e.logLikelihood()), innovation,
                t ? th[3] : Double.NaN, e.se(), e.robustSe());
    }

    private static double[] start(double var, double a, double b, boolean t) {
        double w = var * (1 - a - b);
        return t ? new double[]{w, a, b, 8} : new double[]{w, a, b};
    }

    private record Model(double h0, boolean studentT) implements GarchEstimation.Model {
        @Override
        public int size() {
            return studentT ? 4 : 3;
        }

        @Override
        public double[] toTheta(double[] u) {
            double p = GarchEstimation.logistic(u[1]);
            double sh = GarchEstimation.logistic(u[2]);
            double[] th = new double[size()];
            th[0] = Math.exp(u[0]);
            th[1] = p * sh;
            th[2] = p * (1 - sh);
            if (studentT) {
                th[3] = GarchEstimation.nuFromU(u[3]);
            }
            return th;
        }

        @Override
        public double[] toU(double[] th) {
            double p = th[1] + th[2];
            double[] u = new double[size()];
            u[0] = Math.log(th[0]);
            u[1] = GarchEstimation.logit(p);
            u[2] = GarchEstimation.logit(th[1] / p);
            if (studentT) {
                u[3] = GarchEstimation.uFromNu(th[3]);
            }
            return u;
        }

        @Override
        public double logLik(double[] th, double[] r, double[] perObs) {
            double omega = th[0];
            double alpha = th[1];
            double beta = th[2];
            double nu = studentT ? th[3] : Double.NaN;
            if (!(omega > 0) || alpha < 0 || beta < 0 || (studentT && !(nu > 2))) {
                return Double.NEGATIVE_INFINITY;
            }
            double c = GarchEstimation.tConst(nu);
            double h = h0;
            double ll = 0;
            for (int i = 0; i < r.length; i++) {
                double x = r[i];
                double li = GarchEstimation.logDensity(x, h, nu, c);
                if (perObs != null) {
                    perObs[i] = li;
                }
                ll += li;
                h = omega + alpha * x * x + beta * h;
                if (!(h > 0) || h == Double.POSITIVE_INFINITY) {
                    return Double.NEGATIVE_INFINITY;
                }
            }
            return Double.isFinite(ll) ? ll : Double.NEGATIVE_INFINITY;
        }
    }

    /**
     * Conditional variance series under the fitted parameters, seeded at the
     * sample variance of the demeaned returns (the same backcast the fit uses).
     */
    public static double[] conditionalVariances(double[] returns, Params params) {
        double mean = MathUtils.mean(returns);
        double[] h = new double[returns.length];
        h[0] = MathUtils.variance(returns);
        for (int t = 1; t < returns.length; t++) {
            double x = returns[t - 1] - mean;
            h[t] = params.omega() + params.alpha() * x * x + params.beta() * h[t - 1];
        }
        return h;
    }

    /**
     * k-step-ahead variance forecast:
     * {@code h_{T+k} = σ̄² + (α+β)^{k-1} (h_{T+1} - σ̄²)} — mean-reverts to the
     * unconditional variance at the persistence rate.
     */
    public static double forecastVariance(double[] returns, Params params, int horizon) {
        if (horizon < 1) {
            throw new IllegalArgumentException("horizon must be >= 1");
        }
        double mean = MathUtils.mean(returns);
        double[] h = conditionalVariances(returns, params);
        double lastR = returns[returns.length - 1] - mean;
        double next = params.omega() + params.alpha() * lastR * lastR
                + params.beta() * h[h.length - 1];
        double uncond = params.unconditionalVariance();
        return uncond + Math.pow(params.persistence(), horizon - 1) * (next - uncond);
    }
}
