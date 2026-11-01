package com.quantfinlib.alpha;

/**
 * Heteroskedasticity and autocorrelation consistent (HAC) standard errors
 * with Bartlett weights (Newey and West 1987, Econometrica 55(3), eq. 2).
 *
 * <p>Why it matters here: an IC series computed on overlapping
 * {@code h}-bar forward returns is mechanically MA({@code h−1}), and a
 * persistent signal (12-1 momentum, value, low volatility) adds positive
 * autocorrelation on top. The iid standard error {@code s/√n} then
 * understates the true one by roughly {@code √((1+ρ)/(1−ρ))} for an AR(1)
 * with coefficient {@code ρ}: a factor of three at {@code ρ = 0.8}.</p>
 *
 * <p>The long-run variance of a series {@code x} is estimated as</p>
 * <pre>
 *   Ω = γ₀ + 2 Σ_{l=1..L} (1 − l/(L+1)) γ_l,   γ_l = (1/n) Σ_t (x_t − x̄)(x_{t−l} − x̄)
 * </pre>
 * <p>and the standard error of the mean is {@code √(Ω/n)}. The Bartlett
 * weights keep Ω non-negative. Note {@code γ₀} uses the {@code 1/n}
 * divisor, so at {@code L = 0} the result is {@code s_pop/√n}, slightly
 * below the sample-variance version for small n.</p>
 *
 * <p>Lag choice: {@link #automaticLag} is the Andrews (1991, Econometrica
 * 59(3), eq. 6.2 and Table I) AR(1) plug-in bandwidth for the Bartlett
 * kernel; {@link #lagFor} combines it with the {@code h − 1} floor that
 * overlapping {@code h}-bar windows require.</p>
 */
public final class NeweyWest {

    private NeweyWest() {
    }

    /** HAC long-run variance Ω of {@code x} with Bartlett lag {@code lag}. */
    public static double longRunVariance(double[] x, int lag) {
        int n = x.length;
        if (n < 2) {
            throw new IllegalArgumentException("need at least 2 observations");
        }
        if (lag < 0) {
            throw new IllegalArgumentException("lag must be >= 0");
        }
        int l = Math.min(lag, n - 1);
        double mean = 0;
        for (double v : x) {
            mean += v;
        }
        mean /= n;
        double omega = autocovariance(x, mean, 0);
        for (int k = 1; k <= l; k++) {
            omega += 2 * (1 - k / (l + 1.0)) * autocovariance(x, mean, k);
        }
        return Math.max(0, omega);
    }

    /** HAC standard error of the sample mean of {@code x}. */
    public static double standardErrorOfMean(double[] x, int lag) {
        return Math.sqrt(longRunVariance(x, lag) / x.length);
    }

    /**
     * HAC t-statistic of the mean against zero: {@code mean / SE_HAC}.
     * Zero for a constant series with zero mean; ±∞ for a constant non-zero
     * series (no sampling variation at all).
     */
    public static double tStat(double[] x, int lag) {
        double mean = 0;
        for (double v : x) {
            mean += v;
        }
        mean /= x.length;
        double se = standardErrorOfMean(x, lag);
        if (se == 0) {
            return mean == 0 ? 0 : Math.signum(mean) * Double.POSITIVE_INFINITY;
        }
        return mean / se;
    }

    /** Lag-{@code k} sample autocorrelation {@code γ_k / γ₀} (0 for a constant series). */
    public static double autocorrelation(double[] x, int k) {
        if (k <= 0 || k >= x.length) {
            throw new IllegalArgumentException("lag must be in [1, n-1]");
        }
        double mean = 0;
        for (double v : x) {
            mean += v;
        }
        mean /= x.length;
        double g0 = autocovariance(x, mean, 0);
        return g0 == 0 ? 0 : autocovariance(x, mean, k) / g0;
    }

    /**
     * Andrews (1991) AR(1) plug-in bandwidth for the Bartlett kernel:
     * {@code S = 1.1447 (α̂ n)^{1/3}} with
     * {@code α̂ = 4ρ̂² / ((1−ρ̂)²(1+ρ̂)²)}, returned as an integer lag
     * {@code floor(S)} capped at {@code n/2}. {@code ρ̂} is clamped to
     * {@code [−0.97, 0.97]} so a near-unit-root series cannot request an
     * unbounded window.
     */
    public static int automaticLag(double[] x) {
        int n = x.length;
        if (n < 3) {
            return 0;
        }
        double rho = Math.max(-0.97, Math.min(0.97, autocorrelation(x, 1)));
        double alpha = 4 * rho * rho / (Math.pow(1 - rho, 2) * Math.pow(1 + rho, 2));
        double s = 1.1447 * Math.cbrt(alpha * n);
        return Math.min(n / 2, (int) Math.floor(s));
    }

    /**
     * Lag for a series of statistics computed on overlapping
     * {@code overlap}-bar windows: {@code max(overlap − 1, automaticLag(x))}.
     */
    public static int lagFor(double[] x, int overlap) {
        return Math.min(x.length - 1, Math.max(overlap - 1, automaticLag(x)));
    }

    private static double autocovariance(double[] x, double mean, int k) {
        double s = 0;
        for (int t = k; t < x.length; t++) {
            s += (x[t] - mean) * (x[t - k] - mean);
        }
        return s / x.length;
    }
}
