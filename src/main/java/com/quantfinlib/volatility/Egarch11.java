package com.quantfinlib.volatility;

import com.quantfinlib.util.MathUtils;

/**
 * EGARCH(1,1) — Nelson's exponential GARCH, the log-variance dynamics
 * the plain family cannot express:
 *
 * <pre>  ln h_t = ω + β·ln h_{t-1} + α(|z_{t-1}| − √(2/π)) + γ·z_{t-1}</pre>
 *
 * with standardized shocks {@code z = r/√h}. Two things the log form
 * buys. First, no positivity constraints at all: any parameter signs give
 * a valid variance, because the exp does the work {@code Garch11}'s
 * ω,α,β ≥ 0 constraints do. Second, leverage as a sign: {@code γ < 0}
 * means a down move raises tomorrow's volatility more than an equal up
 * move, and the magnitude reads directly. Stationarity is
 * {@code |β| < 1}, nothing else.
 *
 * <p>Estimation is full maximum likelihood over (ω, α, γ, β) and, for
 * {@link Innovation#STUDENT_T}, ν, with ω, α, γ free and {@code β = tanh(u)} so
 * that {@code |β| < 1}. Nelder-Mead from several starts, observed-
 * information and Bollerslev-Wooldridge robust standard errors, recursion
 * started at {@code ln h₀ = ln σ̂²} (Nelson 1991, Econometrica 59,
 * 347-370). The centring constant is {@code √(2/π)} for both
 * innovations, so {@link Params} drives the same recursion either way;
 * under Student-t the difference to the t value of {@code E|z|} is absorbed
 * in ω. One-step-ahead {@link #nextVariance} is exact; multi-
 * step forecasts are not offered: iterating the log recursion gives the
 * median variance, not the mean ({@code Garch11}/{@code GjrGarch11}
 * forecast multi-step; use them when you need horizons). Research lane.</p>
 */
public final class Egarch11 {

    private static final double E_ABS_Z = Math.sqrt(2 / Math.PI);

    public record Params(double omega, double alpha, double gamma, double beta,
                         double logLikelihood) {

        /** Long-run (unconditional) LOG variance ω/(1−β). */
        public double unconditionalLogVariance() {
            return omega / (1 - beta);
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

    private Egarch11() {
    }

    /** Gaussian maximum-likelihood fit; see {@link #estimate}. */
    public static Params fit(double[] returns) {
        return estimate(returns, Innovation.GAUSSIAN).params();
    }

    /** Maximum-likelihood fit with standard errors (at least 100 finite returns). */
    public static Fit estimate(double[] returns, Innovation innovation) {
        double[] r = GarchEstimation.demean(returns);
        double lnVar = Math.log(MathUtils.variance(r));
        boolean t = innovation == Innovation.STUDENT_T;
        Model model = new Model(lnVar, t);
        double[][] starts = {
                start(lnVar, 0.10, 0.0, 0.95, t), start(lnVar, 0.20, -0.10, 0.80, t),
                start(lnVar, 0.05, 0.0, 0.99, t), start(lnVar, 0.30, 0.0, 0.30, t)};
        GarchEstimation.Estimate e = GarchEstimation.estimate(model, r, starts);
        double[] th = e.theta();
        return new Fit(new Params(th[0], th[1], th[2], th[3], e.logLikelihood()), innovation,
                t ? th[4] : Double.NaN, e.se(), e.robustSe());
    }

    private static double[] start(double lnVar, double a, double g, double b, boolean t) {
        double w = (1 - b) * lnVar;
        return t ? new double[]{w, a, g, b, 8} : new double[]{w, a, g, b};
    }

    private record Model(double lnH0, boolean studentT) implements GarchEstimation.Model {
        @Override
        public int size() {
            return studentT ? 5 : 4;
        }

        @Override
        public double[] toTheta(double[] u) {
            double[] th = new double[size()];
            th[0] = u[0];
            th[1] = u[1];
            th[2] = u[2];
            th[3] = Math.tanh(u[3]);
            if (studentT) {
                th[4] = GarchEstimation.nuFromU(u[4]);
            }
            return th;
        }

        @Override
        public double[] toU(double[] th) {
            double[] u = new double[size()];
            u[0] = th[0];
            u[1] = th[1];
            u[2] = th[2];
            u[3] = 0.5 * Math.log((1 + th[3]) / (1 - th[3]));
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
            if (!(Math.abs(beta) < 1) || (studentT && !(nu > 2))) {
                return Double.NEGATIVE_INFINITY;
            }
            double c = GarchEstimation.tConst(nu);
            double eAbs = E_ABS_Z;
            double lnH = lnH0;
            double ll = 0;
            for (int i = 0; i < r.length; i++) {
                // A runaway log-variance means these parameters are junk for
                // this data: reject rather than overflow exp().
                if (!Double.isFinite(lnH) || Math.abs(lnH) > 100) {
                    return Double.NEGATIVE_INFINITY;
                }
                double h = Math.exp(lnH);
                double x = r[i];
                double li = GarchEstimation.logDensity(x, h, nu, c);
                if (perObs != null) {
                    perObs[i] = li;
                }
                ll += li;
                double z = x / Math.sqrt(h);
                lnH = omega + beta * lnH + alpha * (Math.abs(z) - eAbs) + gamma * z;
            }
            return Double.isFinite(ll) ? ll : Double.NEGATIVE_INFINITY;
        }
    }

    /**
     * The recursion needs a positive, finite starting variance — a
     * constant or NaN-bearing series would return h = 0 or NaN silently,
     * contradicting the "positive by construction" guarantee.
     */
    private static void requireSeries(double[] returns) {
        if (returns.length < 2) {
            throw new IllegalArgumentException("need >= 2 returns");
        }
        for (double x : returns) {
            if (!Double.isFinite(x)) {
                throw new IllegalArgumentException("returns must be finite");
            }
        }
        if (!(MathUtils.variance(returns) > 0)) {
            throw new IllegalArgumentException("returns carry no variance");
        }
    }

    /** Conditional variance series under the fitted parameters. */
    public static double[] conditionalVariances(double[] returns, Params p) {
        requireSeries(returns);
        double mean = MathUtils.mean(returns);
        double[] h = new double[returns.length];
        double lnH = Math.log(MathUtils.variance(returns));
        h[0] = Math.exp(lnH);
        for (int t = 1; t < returns.length; t++) {
            double x = returns[t - 1] - mean;
            double z = x / Math.sqrt(h[t - 1]);
            lnH = p.omega() + p.beta() * lnH
                    + p.alpha() * (Math.abs(z) - E_ABS_Z) + p.gamma() * z;
            h[t] = Math.exp(lnH);
        }
        return h;
    }

    /** One-step-ahead variance — exact (tomorrow's ln h is deterministic today). */
    public static double nextVariance(double[] returns, Params p) {
        double[] h = conditionalVariances(returns, p);
        double mean = MathUtils.mean(returns);
        double x = returns[returns.length - 1] - mean;
        double last = h[h.length - 1];
        double z = x / Math.sqrt(last);
        return Math.exp(p.omega() + p.beta() * Math.log(last)
                + p.alpha() * (Math.abs(z) - E_ABS_Z) + p.gamma() * z);
    }
}
