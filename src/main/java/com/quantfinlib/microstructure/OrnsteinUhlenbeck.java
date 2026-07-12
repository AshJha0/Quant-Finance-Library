package com.quantfinlib.microstructure;

/**
 * Ornstein-Uhlenbeck estimation — the mean-reversion engine under every
 * pairs trade and basis position: {@code dx = κ(θ − x)dt + σ dW}. Fit
 * from a sampled series by exact AR(1) mapping ({@code x_{t+1} = a +
 * b·x_t + ε} with {@code b = e^{−κΔt}}), giving the three numbers a
 * spread trader actually uses:
 *
 * <ul>
 *   <li><b>half-life</b> {@code ln2/κ} — how long the spread takes to
 *       close half its gap: the holding-period estimate, and the first
 *       filter (a 200-day half-life is not a trade);</li>
 *   <li><b>z-score</b> {@code (x − θ)/σ_stat} with the STATIONARY
 *       stdev {@code σ/√(2κ)} — entry/exit in units the strategy can
 *       threshold;</li>
 *   <li><b>the refusal</b>: a fitted {@code b ≥ 1} means the series
 *       shows NO mean reversion in-sample — the fit throws rather than
 *       reporting an infinite half-life as a tradable number, because
 *       fitting OU to a random walk is how pairs desks die.</li>
 * </ul>
 *
 * <p>Small-sample honesty: the OLS AR(1) slope is DOWNWARD-biased in
 * finite samples (Kendall: {@code E[b_hat - b] ~ -(1+3b)/n}), so near the
 * minimum n=30 the fitted κ runs high and the half-life SHORT — a
 * 20-day true half-life can fit as ~12. Treat short-sample half-lives as
 * optimistic lower bounds and prefer n in the hundreds before sizing a
 * holding period off them. Stated, not corrected: bias corrections trade
 * variance for bias and are themselves sample-size-sensitive.</p>
 *
 * <p>Static, deterministic, research lane. Pair with
 * {@code hedging.CointegrationTest} (is the spread stationary at all?)
 * and hand the trade to {@code execution.SpreadExecutionAlgo}.</p>
 */
public final class OrnsteinUhlenbeck {

    /**
     * @param kappa    mean-reversion speed, per unit of {@code dt}'s time
     * @param theta    long-run mean
     * @param sigma    diffusion volatility (same time units as κ)
     * @param halfLife ln2/κ, in {@code dt} time units
     */
    public record Params(double kappa, double theta, double sigma, double halfLife) {

        /** Stationary standard deviation σ/√(2κ) — the z-score's yardstick. */
        public double stationaryStdev() {
            return sigma / Math.sqrt(2 * kappa);
        }

        /** (x − θ) / stationary stdev: the entry/exit signal. */
        public double zScore(double x) {
            if (!Double.isFinite(x)) {
                throw new IllegalArgumentException("x must be finite");
            }
            return (x - theta) / stationaryStdev();
        }
    }

    private OrnsteinUhlenbeck() {
    }

    /**
     * Fits OU to a series sampled every {@code dt} time units (e.g.
     * dt = 1.0/252 for daily samples in years). Throws when the series
     * shows no mean reversion — see class doc.
     *
     * @param series ≥ 30 finite observations
     * @param dt     sampling interval, &gt; 0
     */
    public static Params fit(double[] series, double dt) {
        if (series.length < 30) {
            throw new IllegalArgumentException("need >= 30 observations, got " + series.length);
        }
        if (!(dt > 0) || dt == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("dt must be positive and finite");
        }
        for (double x : series) {
            if (!Double.isFinite(x)) {
                throw new IllegalArgumentException("series must be finite");
            }
        }
        // AR(1) OLS: x_{t+1} = a + b x_t + e.
        int n = series.length - 1;
        double sx = 0;
        double sy = 0;
        double sxx = 0;
        double sxy = 0;
        for (int t = 0; t < n; t++) {
            sx += series[t];
            sy += series[t + 1];
            sxx += series[t] * series[t];
            sxy += series[t] * series[t + 1];
        }
        double denom = n * sxx - sx * sx;
        if (denom <= 0) {
            throw new IllegalArgumentException("degenerate series (constant?)");
        }
        double b = (n * sxy - sx * sy) / denom;
        double a = (sy - b * sx) / n;
        if (!(b > 0 && b < 1)) {
            throw new IllegalArgumentException("fitted AR coefficient " + b
                    + " is outside (0, 1): the series shows no mean reversion "
                    + "in-sample — an OU fit here would be a random walk in costume");
        }
        double kappa = -Math.log(b) / dt;
        double theta = a / (1 - b);
        // Residual variance -> diffusion sigma via the exact discretization:
        // Var(e) = sigma^2 (1 - b^2) / (2 kappa).
        double sse = 0;
        for (int t = 0; t < n; t++) {
            double e = series[t + 1] - a - b * series[t];
            sse += e * e;
        }
        double varE = sse / (n - 2);
        double sigma = Math.sqrt(varE * 2 * kappa / (1 - b * b));
        return new Params(kappa, theta, sigma, Math.log(2) / kappa);
    }

    /** Convenience: the fitted z-score of the LAST observation. */
    public static double lastZScore(double[] series, double dt) {
        Params p = fit(series, dt);
        return p.zScore(series[series.length - 1]);
    }
}
