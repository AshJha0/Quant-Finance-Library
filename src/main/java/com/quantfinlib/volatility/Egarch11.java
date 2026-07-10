package com.quantfinlib.volatility;

import com.quantfinlib.util.MathUtils;

/**
 * EGARCH(1,1) — Nelson's exponential GARCH, the LOG-variance dynamics
 * the plain family cannot express:
 *
 * <pre>  ln h_t = ω + β·ln h_{t-1} + α(|z_{t-1}| − √(2/π)) + γ·z_{t-1}</pre>
 *
 * with standardized shocks {@code z = r/√h}. Two things the log form
 * buys: no positivity constraints AT ALL (any parameter signs give a
 * valid variance — the exp does the work {@code Garch11}'s ω,α,β ≥ 0
 * constraints do), and leverage as a SIGN — {@code γ < 0} means a
 * down move raises tomorrow's volatility more than an equal up move,
 * and the magnitude reads directly. Stationarity is {@code |β| < 1},
 * nothing else.
 *
 * <p>Estimation mirrors the family: Gaussian MLE over a coarse-to-fine
 * grid spanning the EMPIRICALLY PLAUSIBLE box — α ∈ [0, 0.9],
 * γ ∈ [−0.9, 0.9], β ∈ [0, 0.995]. Negative α or β, while formally
 * admissible in the log form, are not searched (stated, not hidden:
 * they describe oscillating log-variance no asset-return series
 * exhibits). ω is targeted to the sample's log variance
 * ({@code ω = (1−β)·ln σ̄²} — an approximation, since
 * {@code E[ln h] ≤ ln E[h]} by Jensen; stated, not hidden). One-step-ahead {@link #nextVariance} is exact; multi-
 * step forecasts are deliberately NOT offered — iterating the log
 * recursion forecasts the MEDIAN variance, not the mean, and quietly
 * returning it as "the forecast" is the kind of lie this library
 * refuses ({@code Garch11}/{@code GjrGarch11} forecast multi-step
 * honestly; use them when you need horizons). Research lane.</p>
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

    private Egarch11() {
    }

    /** Fits EGARCH(1,1) to (demeaned) returns by grid MLE. */
    public static Params fit(double[] returns) {
        if (returns.length < 100) {
            throw new IllegalArgumentException("need at least 100 returns, got "
                    + returns.length);
        }
        double mean = MathUtils.mean(returns);
        double[] r = new double[returns.length];
        for (int i = 0; i < r.length; i++) {
            r[i] = returns[i] - mean;
        }
        double sampleVar = MathUtils.variance(r);
        if (!(sampleVar > 0)) {
            throw new IllegalArgumentException("returns carry no variance");
        }
        double lnVar = Math.log(sampleVar);

        double bestAlpha = 0.1;
        double bestGamma = 0;
        double bestBeta = 0.9;
        double bestLl = Double.NEGATIVE_INFINITY;
        // The empirically plausible box (see class doc): |β| < 1 is the
        // only formal constraint, but negative α/β describe oscillating
        // log-variance no return series exhibits and are not searched.
        double aLo = 0.0, aHi = 0.9;
        double gLo = -0.9, gHi = 0.9;
        double bLo = 0.0, bHi = 0.995;

        for (int pass = 0; pass < 3; pass++) {
            int grid = 12;
            for (int i = 0; i <= grid; i++) {
                double alpha = aLo + (aHi - aLo) * i / grid;
                for (int j = 0; j <= grid; j++) {
                    double gamma = gLo + (gHi - gLo) * j / grid;
                    for (int k = 0; k <= grid; k++) {
                        double beta = bLo + (bHi - bLo) * k / grid;
                        double ll = logLikelihood(r, lnVar, alpha, gamma, beta);
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
            aHi = Math.min(0.9, bestAlpha + 2 * aStep);
            gLo = Math.max(-0.9, bestGamma - 2 * gStep);
            gHi = Math.min(0.9, bestGamma + 2 * gStep);
            bLo = Math.max(0, bestBeta - 2 * bStep);
            bHi = Math.min(0.995, bestBeta + 2 * bStep);
        }
        double omega = (1 - bestBeta) * lnVar;
        return new Params(omega, bestAlpha, bestGamma, bestBeta, bestLl);
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

    private static double logLikelihood(double[] r, double lnSampleVar,
                                        double alpha, double gamma, double beta) {
        double lnH = lnSampleVar;
        double omega = (1 - beta) * lnSampleVar;
        double ll = 0;
        for (double x : r) {
            // A runaway log-variance means these parameters are junk for
            // this data — reject the cell rather than overflow exp().
            if (!Double.isFinite(lnH) || Math.abs(lnH) > 100) {
                return Double.NEGATIVE_INFINITY;
            }
            double h = Math.exp(lnH);
            ll += -0.5 * (Math.log(2 * Math.PI) + lnH + x * x / h);
            double z = x / Math.sqrt(h);
            lnH = omega + beta * lnH + alpha * (Math.abs(z) - E_ABS_Z) + gamma * z;
        }
        return ll;
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

    /** One-step-ahead variance — EXACT (tomorrow's ln h is deterministic today). */
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
