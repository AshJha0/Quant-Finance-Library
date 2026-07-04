package com.quantfinlib.volatility;

import com.quantfinlib.util.MathUtils;

/**
 * GARCH(1,1) volatility model with Gaussian maximum-likelihood fitting:
 * {@code h_t = ω + α r_{t-1}² + β h_{t-1}}.
 *
 * <p>Estimation uses variance targeting ({@code ω = σ̄²(1-α-β)}, which pins
 * the unconditional variance to the sample variance) and a coarse-to-fine
 * grid search over (α, β) — derivative-free, deterministic, and robust for a
 * two-parameter surface.</p>
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

    private Garch11() {
    }

    /** Fits GARCH(1,1) to (demeaned) returns by MLE with variance targeting. */
    public static Params fit(double[] returns) {
        if (returns.length < 100) {
            throw new IllegalArgumentException("need at least 100 returns, got " + returns.length);
        }
        double mean = MathUtils.mean(returns);
        double[] r = new double[returns.length];
        for (int i = 0; i < r.length; i++) {
            r[i] = returns[i] - mean;
        }
        double sampleVar = MathUtils.variance(r);

        double bestAlpha = 0.05, bestBeta = 0.9;
        double bestLl = Double.NEGATIVE_INFINITY;
        double alphaLo = 0.005, alphaHi = 0.40, betaLo = 0.40, betaHi = 0.995;

        for (int pass = 0; pass < 3; pass++) {
            int grid = 25;
            for (int i = 0; i <= grid; i++) {
                double alpha = alphaLo + (alphaHi - alphaLo) * i / grid;
                for (int j = 0; j <= grid; j++) {
                    double beta = betaLo + (betaHi - betaLo) * j / grid;
                    if (alpha + beta >= 0.9995 || alpha <= 0 || beta <= 0) {
                        continue;
                    }
                    double ll = logLikelihood(r, sampleVar, alpha, beta);
                    if (ll > bestLl) {
                        bestLl = ll;
                        bestAlpha = alpha;
                        bestBeta = beta;
                    }
                }
            }
            // Zoom into the best cell for the next pass.
            double alphaStep = (alphaHi - alphaLo) / grid;
            double betaStep = (betaHi - betaLo) / grid;
            alphaLo = Math.max(1e-6, bestAlpha - 2 * alphaStep);
            alphaHi = Math.min(0.99, bestAlpha + 2 * alphaStep);
            betaLo = Math.max(1e-6, bestBeta - 2 * betaStep);
            betaHi = Math.min(0.999, bestBeta + 2 * betaStep);
        }
        double omega = sampleVar * (1 - bestAlpha - bestBeta);
        return new Params(omega, bestAlpha, bestBeta, bestLl);
    }

    private static double logLikelihood(double[] r, double sampleVar, double alpha, double beta) {
        double omega = sampleVar * (1 - alpha - beta);
        double h = sampleVar;
        double ll = 0;
        for (double x : r) {
            if (h <= 0) {
                return Double.NEGATIVE_INFINITY;
            }
            ll += -0.5 * (Math.log(2 * Math.PI) + Math.log(h) + x * x / h);
            h = omega + alpha * x * x + beta * h;
        }
        return ll;
    }

    /** Conditional variance series under the fitted parameters (seeded at sample variance). */
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
