package com.quantfinlib.volatility;

import com.quantfinlib.util.MathUtils;

/**
 * Exponentially weighted moving average variance (RiskMetrics-style):
 * {@code h_t = λ h_{t-1} + (1-λ) r_{t-1}²}, seeded with the sample variance.
 * Reacts to volatility regime changes far faster than a rolling window.
 */
public final class EwmaVolatility {

    private final double lambda;

    public EwmaVolatility(double lambda) {
        if (lambda <= 0 || lambda >= 1) {
            throw new IllegalArgumentException("lambda must be in (0,1): " + lambda);
        }
        this.lambda = lambda;
    }

    /** The classic RiskMetrics daily decay (λ = 0.94). */
    public static EwmaVolatility riskMetrics() {
        return new EwmaVolatility(0.94);
    }

    /**
     * Conditional variance series aligned with returns:
     * {@code variances[i]} is the estimate for period i, formed from
     * information up to i-1.
     */
    public double[] variances(double[] returns) {
        if (returns.length < 2) {
            throw new IllegalArgumentException("need at least 2 returns");
        }
        double[] h = new double[returns.length];
        h[0] = MathUtils.variance(returns);   // unconditional seed
        for (int t = 1; t < returns.length; t++) {
            h[t] = lambda * h[t - 1] + (1 - lambda) * returns[t - 1] * returns[t - 1];
        }
        return h;
    }

    /** One-step-ahead volatility forecast (per period). */
    public double latestVol(double[] returns) {
        double[] h = variances(returns);
        double last = h[h.length - 1];
        double next = lambda * last + (1 - lambda) * returns[returns.length - 1] * returns[returns.length - 1];
        return Math.sqrt(next);
    }

    public double annualizedVol(double[] returns, int periodsPerYear) {
        return latestVol(returns) * Math.sqrt(periodsPerYear);
    }
}
