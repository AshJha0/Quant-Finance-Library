package com.quantfinlib.volatility;

/**
 * Exponentially weighted moving average variance (RiskMetrics Technical
 * Document, J.P. Morgan 1996, ch. 5): {@code h_t = λ h_{t-1} + (1-λ) r_{t-1}²}
 * on raw (not demeaned) returns. Reacts to volatility regime changes far
 * faster than a rolling window.
 *
 * <p>Seeding is causal. {@link #variances(double[])} has no prior estimate,
 * so for {@code t >= 1} it uses the bias-corrected weights
 * {@code h_t = Σ_{k<t} (1−λ)λ^{t−1−k} r_k² / (1 − λ^t)}, which use only
 * returns before period t; {@code h_0} has no information at all and is set
 * to {@code h_1 = r_0²}, the one entry that sees its own period's return.
 * {@link #variances(double[], double)} takes an explicit prior variance
 * (from a training window) and runs the plain recursion from it. The
 * earlier full-sample seed leaked the whole sample into every early
 * estimate.</p>
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
        requireReturns(returns);
        double[] h = new double[returns.length];
        double raw = 0;            // Σ (1−λ)λ^{t−1−k} r_k², uncorrected
        double mass = 0;           // 1 − λ^t
        for (int t = 1; t < returns.length; t++) {
            raw = lambda * raw + (1 - lambda) * returns[t - 1] * returns[t - 1];
            mass = lambda * mass + (1 - lambda);
            h[t] = raw / mass;
        }
        h[0] = h[1];
        return h;
    }

    /**
     * Conditional variances from an explicit prior: {@code h_0 = seedVariance},
     * then the plain recursion.
     *
     * @param seedVariance variance estimate for period 0 formed before the
     *                     sample starts, &gt; 0 and finite
     */
    public double[] variances(double[] returns, double seedVariance) {
        requireReturns(returns);
        if (!(seedVariance > 0) || seedVariance == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("seedVariance must be > 0 and finite");
        }
        double[] h = new double[returns.length];
        h[0] = seedVariance;
        for (int t = 1; t < returns.length; t++) {
            h[t] = lambda * h[t - 1] + (1 - lambda) * returns[t - 1] * returns[t - 1];
        }
        return h;
    }

    private static void requireReturns(double[] returns) {
        if (returns.length < 2) {
            throw new IllegalArgumentException("need at least 2 returns");
        }
        for (double r : returns) {
            if (!Double.isFinite(r)) {
                throw new IllegalArgumentException("returns must be finite");
            }
        }
    }

    /**
     * One-step-ahead volatility forecast (per period) for the period after
     * the last return, with the same bias-corrected causal weights as
     * {@link #variances(double[])}.
     */
    public double latestVol(double[] returns) {
        double[] h = variances(returns);
        int n = returns.length;
        double mass = 1 - Math.pow(lambda, n - 1);
        double r = returns[n - 1];
        double next = (lambda * mass * h[n - 1] + (1 - lambda) * r * r)
                / (lambda * mass + (1 - lambda));
        return Math.sqrt(next);
    }

    public double annualizedVol(double[] returns, int periodsPerYear) {
        return latestVol(returns) * Math.sqrt(periodsPerYear);
    }
}
