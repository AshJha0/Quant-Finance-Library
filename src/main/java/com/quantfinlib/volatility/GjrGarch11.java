package com.quantfinlib.volatility;

import com.quantfinlib.util.MathUtils;

/**
 * GJR-GARCH(1,1,1) — GARCH with the LEVERAGE term equity markets demand:
 *
 * <pre>  h_t = ω + (α + γ·1{r&lt;0}) r_{t-1}² + β h_{t-1}</pre>
 *
 * A down move raises tomorrow's variance by {@code α + γ}; an equal up
 * move by only {@code α}. That asymmetry (Glosten-Jagannathan-Runkle,
 * 1993) is not a refinement — on equity indices γ is typically LARGER
 * than α, i.e. most of the ARCH effect is the leverage effect, and a
 * symmetric {@link Garch11} systematically underestimates post-selloff
 * volatility. Fitting a GJR and finding γ ≈ 0 is itself information:
 * the series has no asymmetry and the simpler model suffices (FX pairs
 * often land there; equity indices rarely do).
 *
 * <p>Estimation mirrors {@code Garch11}: Gaussian MLE with variance
 * targeting ({@code ω = σ̄²(1 − α − γ/2 − β)}, since negative returns
 * carry half the mass under symmetry) and a coarse-to-fine grid over
 * (α, γ, β) — derivative-free, deterministic, robust. Same 100-return
 * minimum, same conditional-variance and mean-reverting forecast
 * accessors, with persistence {@code α + γ/2 + β}. Research lane.</p>
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

    private GjrGarch11() {
    }

    /** Fits GJR-GARCH(1,1) to (demeaned) returns by MLE with variance targeting. */
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

        double bestAlpha = 0.03;
        double bestGamma = 0.05;
        double bestBeta = 0.9;
        double bestLl = Double.NEGATIVE_INFINITY;
        // Initial boxes span the FULL admissible region (the persistence
        // constraint α + γ/2 + β < 1 prunes invalid cells) — a narrower
        // starting box is a hard cap the refinement passes can only creep
        // past by ~2 steps per pass, silently pinning extreme fits at the
        // box edge.
        double aLo = 0.0, aHi = 0.99;
        double gLo = 0.0, gHi = 1.9;
        double bLo = 1e-6, bHi = 0.999;

        for (int pass = 0; pass < 3; pass++) {
            int grid = 12;                 // 13³ ≈ 2,200 cells per pass
            for (int i = 0; i <= grid; i++) {
                double alpha = aLo + (aHi - aLo) * i / grid;
                for (int j = 0; j <= grid; j++) {
                    double gamma = gLo + (gHi - gLo) * j / grid;
                    for (int k = 0; k <= grid; k++) {
                        double beta = bLo + (bHi - bLo) * k / grid;
                        if (alpha < 0 || gamma < 0 || beta <= 0
                                || alpha + gamma / 2 + beta >= 0.9995) {
                            continue;
                        }
                        double ll = logLikelihood(r, sampleVar, alpha, gamma, beta);
                        if (ll > bestLl) {
                            bestLl = ll;
                            bestAlpha = alpha;
                            bestGamma = gamma;
                            bestBeta = beta;
                        }
                    }
                }
            }
            double aStep = (aHi - aLo) / grid;
            double gStep = (gHi - gLo) / grid;
            double bStep = (bHi - bLo) / grid;
            aLo = Math.max(0, bestAlpha - 2 * aStep);
            aHi = Math.min(0.99, bestAlpha + 2 * aStep);
            gLo = Math.max(0, bestGamma - 2 * gStep);
            gHi = Math.min(1.9, bestGamma + 2 * gStep);
            bLo = Math.max(1e-6, bestBeta - 2 * bStep);
            bHi = Math.min(0.999, bestBeta + 2 * bStep);
        }
        double omega = sampleVar * (1 - bestAlpha - bestGamma / 2 - bestBeta);
        return new Params(omega, bestAlpha, bestGamma, bestBeta, bestLl);
    }

    private static double logLikelihood(double[] r, double sampleVar, double alpha,
                                        double gamma, double beta) {
        double omega = sampleVar * (1 - alpha - gamma / 2 - beta);
        if (omega <= 0) {
            return Double.NEGATIVE_INFINITY;
        }
        double h = sampleVar;
        double ll = 0;
        for (double x : r) {
            if (h <= 0) {
                return Double.NEGATIVE_INFINITY;
            }
            ll += -0.5 * (Math.log(2 * Math.PI) + Math.log(h) + x * x / h);
            double arch = x < 0 ? alpha + gamma : alpha;
            h = omega + arch * x * x + beta * h;
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
        double mean = MathUtils.mean(returns);
        double[] h = conditionalVariances(returns, params);
        double lastR = returns[returns.length - 1] - mean;
        double arch = lastR < 0 ? params.alpha() + params.gamma() : params.alpha();
        double next = params.omega() + arch * lastR * lastR + params.beta() * h[h.length - 1];
        double uncond = params.unconditionalVariance();
        return uncond + Math.pow(params.persistence(), horizon - 1) * (next - uncond);
    }
}
