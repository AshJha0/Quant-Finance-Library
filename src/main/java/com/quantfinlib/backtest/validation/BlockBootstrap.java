package com.quantfinlib.backtest.validation;

import com.quantfinlib.risk.RiskMetrics;

import java.util.Random;

/**
 * Stationary block bootstrap (Politis-Romano) — the confidence interval
 * your backtest's Sharpe ratio deserves and almost never gets. A single
 * historical path yields ONE Sharpe estimate; resampling the path in
 * blocks yields its sampling DISTRIBUTION, and the honest question
 * becomes "is the 5th percentile still positive?" rather than "is 1.2
 * a good number?".
 *
 * <p>Why BLOCKS: returns are autocorrelated (vol clusters, trends
 * persist), and resampling single observations (an iid bootstrap)
 * destroys that structure and UNDERSTATES the uncertainty — the
 * classic way to be falsely confident. Blocks of geometric mean length
 * L preserve local dependence; the stationary variant restarts blocks
 * with probability 1/L and wraps circularly, so every resampled path
 * has the full original length. Rule of thumb: L ≈ n^(1/3) (about 10
 * for a 1,000-day history).
 *
 * <p>Deterministic per seed (replayable), and honest about what it is
 * NOT: the bootstrap resamples the history you had — it cannot
 * manufacture regimes the sample never contained. Pair with
 * {@code SharpeValidation} (multiple-testing haircut) and
 * {@code AlphaValidation} (out-of-sample discipline); this class
 * quantifies the sampling error that remains even for an honest,
 * single-trial backtest. Research lane.</p>
 */
public final class BlockBootstrap {

    private BlockBootstrap() {
    }

    /**
     * The bootstrap distribution of ANNUALIZED Sharpe, sorted ascending
     * — read percentiles with {@code MathUtils.percentileSorted}.
     *
     * @param returns         per-period strategy returns, ≥ 50 finite
     * @param meanBlockLength geometric mean block length L, ≥ 1
     *                        (1 = iid bootstrap — only for demonstrating
     *                        why you should not use it)
     * @param resamples       bootstrap paths, ≥ 100
     * @param periodsPerYear  annualization (252 for daily)
     * @param seed            deterministic seed
     */
    public static double[] sharpeSamples(double[] returns, int meanBlockLength,
                                         int resamples, int periodsPerYear, long seed) {
        if (resamples < 100) {
            throw new IllegalArgumentException("need >= 100 resamples for a distribution");
        }
        if (periodsPerYear < 1) {
            throw new IllegalArgumentException("periodsPerYear must be >= 1");
        }
        Random rnd = new Random(seed);
        double[] samples = new double[resamples];
        double[] path = new double[returns.length];
        requireSeries(returns, meanBlockLength);
        for (int s = 0; s < resamples; s++) {
            resampleInto(returns, meanBlockLength, rnd, path);
            samples[s] = RiskMetrics.sharpeRatio(path, 0, periodsPerYear);
        }
        java.util.Arrays.sort(samples);
        return samples;
    }

    /** One stationary-bootstrap path (circular, geometric blocks). */
    public static double[] resample(double[] series, int meanBlockLength, Random rnd) {
        requireSeries(series, meanBlockLength);
        double[] out = new double[series.length];
        resampleInto(series, meanBlockLength, rnd, out);
        return out;
    }

    private static void resampleInto(double[] series, int meanBlockLength,
                                     Random rnd, double[] out) {
        int n = series.length;
        double restart = 1.0 / meanBlockLength;
        int i = rnd.nextInt(n);
        for (int t = 0; t < n; t++) {
            out[t] = series[i];
            i = rnd.nextDouble() < restart ? rnd.nextInt(n) : (i + 1) % n;
        }
    }

    private static void requireSeries(double[] series, int meanBlockLength) {
        if (series.length < 50) {
            throw new IllegalArgumentException("need >= 50 observations");
        }
        if (meanBlockLength < 1) {
            throw new IllegalArgumentException("meanBlockLength must be >= 1");
        }
        for (double r : series) {
            if (!Double.isFinite(r)) {
                throw new IllegalArgumentException("returns must be finite");
            }
        }
    }
}
