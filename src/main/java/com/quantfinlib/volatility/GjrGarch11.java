package com.quantfinlib.volatility;

import com.quantfinlib.stats.Stats;

/**
 * GJR-GARCH(1,1,1) — GARCH with the leverage term equity markets demand:
 *
 * <pre>  h_t = ω + (α + γ·1{r&lt;0}) r_{t-1}² + β h_{t-1}</pre>
 *
 * A down move raises tomorrow's variance by {@code α + γ}; an equal up
 * move by only {@code α}. That asymmetry (Glosten-Jagannathan-Runkle,
 * 1993) is not a refinement — on equity indices γ is typically larger
 * than α, i.e. most of the ARCH effect is the leverage effect, and a
 * symmetric {@link Garch11} systematically underestimates post-selloff
 * volatility. Fitting a GJR and finding γ ≈ 0 is itself information:
 * the series has no asymmetry and the simpler model suffices (FX pairs
 * often land there; equity indices rarely do).
 *
 * <p>Estimation is full maximum likelihood over (ω, α, γ, β) and, for
 * {@link Innovation#STUDENT_T}, ν, with the same machinery as
 * {@link Garch11}: the region ω &gt; 0, α, γ, β &ge; 0,
 * {@code α + γ/2 + β < 1} is mapped onto R⁴ by {@code ω = e^{u₀}},
 * persistence {@code p = logistic(u₁)} split into α, γ/2, β by a softmax of
 * {@code (u₂, u₃, 0)}; Nelder-Mead from several starts; observed-information
 * and Bollerslev-Wooldridge robust standard errors. The persistence
 * {@code α + γ/2 + β} assumes a symmetric innovation (P(z &lt; 0) = ½).
 * Same 100-return minimum, conditional-variance and mean-reverting forecast
 * accessors. Research lane.</p>
 */
public final class GjrGarch11 {

    public record Params(double omega, double alpha, double gamma, double beta,
                         double logLikelihood) {

        /** α + γ/2 + β — the mean-reversion persistence under symmetric returns. */
        public double persistence() {
            return alpha + gamma / 2 + beta;
        }

        public double unconditionalVariance() {
            return omega / (1 - persistence());
        }
    }

    public static final class Fit {
        private final Params params;
        private final Innovation innovation;
        private final double nu;
        private final double[] standardErrors;
        private final double[] robustStandardErrors;

        /**
         * Creates the value; array and collection arguments are copied.
         *
         * @param params ω, α, γ, β and the maximised log-likelihood
         * @param innovation the innovation distribution used
         * @param nu Student-t degrees of freedom, NaN for Gaussian
         * @param standardErrors SEs of (ω, α, γ, β[, ν]) from the observed information
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

        /** {@code params} (ω, α, γ, β and the maximised log-likelihood). */
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

        /** Element {@code i} of {@code standardErrors} (SEs of (ω, α, γ, β[, ν]) from the observed information). */
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

    private GjrGarch11() {
    }

    /** Gaussian maximum-likelihood fit; see {@link #estimate}. */
    public static Params fit(double[] returns) {
        return estimate(returns, Innovation.GAUSSIAN).params();
    }

    /** Maximum-likelihood fit with standard errors (at least 100 finite returns). */
    public static Fit estimate(double[] returns, Innovation innovation) {
        double[] r = GarchEstimation.demean(returns);
        double var = Stats.sampleVariance(r);
        boolean t = innovation == Innovation.STUDENT_T;
        Model model = new Model(var, t);
        double[][] starts = {
                start(var, 0.03, 0.06, 0.88, t), start(var, 0.10, 0.10, 0.50, t),
                start(var, 0.02, 0.02, 0.95, t), start(var, 0.30, 0.20, 0.20, t)};
        GarchEstimation.Estimate e = GarchEstimation.estimate(model, r, starts);
        double[] th = e.theta();
        return new Fit(new Params(th[0], th[1], th[2], th[3], e.logLikelihood()), innovation,
                t ? th[4] : Double.NaN, e.se(), e.robustSe());
    }

    private static double[] start(double var, double a, double g, double b, boolean t) {
        double w = var * (1 - a - g / 2 - b);
        return t ? new double[]{w, a, g, b, 8} : new double[]{w, a, g, b};
    }

    private record Model(double h0, boolean studentT) implements GarchEstimation.Model {
        @Override
        public int size() {
            return studentT ? 5 : 4;
        }

        @Override
        public double[] toTheta(double[] u) {
            double p = GarchEstimation.logistic(u[1]);
            double m = Math.max(Math.max(u[2], u[3]), 0);
            double e1 = Math.exp(u[2] - m);
            double e2 = Math.exp(u[3] - m);
            double e3 = Math.exp(-m);
            double sum = e1 + e2 + e3;
            double[] th = new double[size()];
            th[0] = Math.exp(u[0]);
            th[1] = p * e1 / sum;
            th[2] = 2 * p * e2 / sum;
            th[3] = p * e3 / sum;
            if (studentT) {
                th[4] = GarchEstimation.nuFromU(u[4]);
            }
            return th;
        }

        @Override
        public double[] toU(double[] th) {
            double p = th[1] + th[2] / 2 + th[3];
            double[] u = new double[size()];
            u[0] = Math.log(th[0]);
            u[1] = GarchEstimation.logit(p);
            u[2] = Math.log(th[1] / th[3]);
            u[3] = Math.log((th[2] / 2) / th[3]);
            if (studentT) {
                u[4] = GarchEstimation.uFromNu(th[4]);
            }
            return u;
        }

        @Override
        public double logLik(double[] th, double[] r, double[] perObs) {
            double omega = th[0];
            double alpha = th[1];
            double gamma = th[2];
            double beta = th[3];
            double nu = studentT ? th[4] : Double.NaN;
            if (!(omega > 0) || alpha < 0 || gamma < 0 || beta < 0 || (studentT && !(nu > 2))) {
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
                double arch = x < 0 ? alpha + gamma : alpha;
                h = omega + arch * x * x + beta * h;
                if (!(h > 0) || h == Double.POSITIVE_INFINITY) {
                    return Double.NEGATIVE_INFINITY;
                }
            }
            return Double.isFinite(ll) ? ll : Double.NEGATIVE_INFINITY;
        }
    }

    /** Conditional variance series under the fitted parameters (seeded at sample variance). */
    public static double[] conditionalVariances(double[] returns, Params params) {
        double mean = Stats.mean(returns);
        double[] h = new double[returns.length];
        h[0] = Stats.sampleVariance(returns);
        for (int t = 1; t < returns.length; t++) {
            double x = returns[t - 1] - mean;
            double arch = x < 0 ? params.alpha() + params.gamma() : params.alpha();
            h[t] = params.omega() + arch * x * x + params.beta() * h[t - 1];
        }
        return h;
    }

    /**
     * k-step-ahead variance forecast — mean-reverts to the unconditional
     * variance at the persistence rate, exactly as {@link Garch11} but
     * with the asymmetric first step.
     */
    public static double forecastVariance(double[] returns, Params params, int horizon) {
        if (horizon < 1) {
            throw new IllegalArgumentException("horizon must be >= 1");
        }
        double mean = Stats.mean(returns);
        double[] h = conditionalVariances(returns, params);
        double lastR = returns[returns.length - 1] - mean;
        double arch = lastR < 0 ? params.alpha() + params.gamma() : params.alpha();
        double next = params.omega() + arch * lastR * lastR + params.beta() * h[h.length - 1];
        double uncond = params.unconditionalVariance();
        return uncond + Math.pow(params.persistence(), horizon - 1) * (next - uncond);
    }
}
