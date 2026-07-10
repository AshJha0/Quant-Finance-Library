package com.quantfinlib.microstructure;

import com.quantfinlib.util.MathUtils;

/**
 * Liquidity estimated from BARS ALONE — the estimators for every
 * market where you have prices but no quotes: history before your tick
 * capture started, less-developed markets, bonds marked once a day, or
 * a 20-year backtest that would otherwise pretend spreads were zero.
 *
 * <ul>
 *   <li><b>Roll (1984)</b> — the effective spread implied by bid-ask
 *       BOUNCE: trade prices ping-ponging between bid and ask create
 *       negative autocovariance in price changes, and
 *       {@code s = 2√(−cov(Δp_t, Δp_{t−1}))}. When the autocovariance
 *       is POSITIVE (trending sample, no bounce signature) the
 *       estimator is undefined and returns NaN — not zero, because
 *       "zero spread" is a claim and NaN is an honest shrug;</li>
 *   <li><b>Corwin-Schultz (2012)</b> — the spread from two days'
 *       HIGH-LOW ranges: variance grows with time but the spread does
 *       not, so comparing one 2-day range against two 1-day ranges
 *       isolates the spread. Negative estimates clamp to 0 (standard
 *       practice, stated);</li>
 *   <li><b>Amihud (2002)</b> — price impact per currency unit traded:
 *       {@code mean(|return| / dollarVolume)}. The cross-sectional
 *       illiquidity ranker — multiply by 1e6 for the conventional
 *       "per million" quotation.</li>
 * </ul>
 *
 * <p>Static, deterministic, research lane. These are ESTIMATORS with
 * real sampling error on short windows — rank with them, do not
 * mark books with them.</p>
 */
public final class LiquidityMeasures {

    private LiquidityMeasures() {
    }

    /**
     * Roll's implied effective spread from trade/close prices (same
     * units as the prices). NaN when the bounce signature is absent —
     * see class doc.
     *
     * @param prices ≥ 3 finite positive prices
     */
    public static double rollSpread(double[] prices) {
        if (prices.length < 3) {
            throw new IllegalArgumentException("need >= 3 prices");
        }
        requirePositiveFinite(prices);
        int n = prices.length - 1;
        double[] d = new double[n];
        for (int i = 0; i < n; i++) {
            d[i] = prices[i + 1] - prices[i];
        }
        // cov(Δp_t, Δp_{t−1}) over the n−1 adjacent pairs.
        double mean = MathUtils.mean(d);
        double cov = 0;
        for (int i = 1; i < n; i++) {
            cov += (d[i] - mean) * (d[i - 1] - mean);
        }
        cov /= (n - 1);
        return cov < 0 ? 2 * Math.sqrt(-cov) : Double.NaN;
    }

    /**
     * Corwin-Schultz high-low spread estimate as a FRACTION of price,
     * from two consecutive periods' highs and lows. Negative estimates
     * clamp to 0 (stated standard practice).
     */
    public static double corwinSchultzSpread(double high1, double low1,
                                             double high2, double low2) {
        requirePositiveFinite(new double[]{high1, low1, high2, low2});
        if (high1 < low1 || high2 < low2) {
            throw new IllegalArgumentException("high must be >= low");
        }
        double b = square(Math.log(high1 / low1)) + square(Math.log(high2 / low2));
        double gamma = square(Math.log(Math.max(high1, high2) / Math.min(low1, low2)));
        double k = 3 - 2 * Math.sqrt(2);
        double alpha = (Math.sqrt(2 * b) - Math.sqrt(b)) / k - Math.sqrt(gamma / k);
        double spread = 2 * (Math.exp(alpha) - 1) / (1 + Math.exp(alpha));
        return Math.max(0, spread);
    }

    /**
     * Amihud illiquidity: {@code mean(|return| / dollarVolume)} —
     * return per currency unit traded. Zero-volume periods are a
     * data problem, not an infinity: they throw.
     *
     * @param returns       per-period returns (fractions), finite
     * @param dollarVolumes per-period traded value, &gt; 0, aligned
     */
    public static double amihudIlliquidity(double[] returns, double[] dollarVolumes) {
        if (returns.length != dollarVolumes.length || returns.length < 1) {
            throw new IllegalArgumentException("need aligned, non-empty arrays");
        }
        double sum = 0;
        for (int i = 0; i < returns.length; i++) {
            if (!Double.isFinite(returns[i])) {
                throw new IllegalArgumentException("returns must be finite");
            }
            if (!(dollarVolumes[i] > 0) || dollarVolumes[i] == Double.POSITIVE_INFINITY) {
                throw new IllegalArgumentException(
                        "dollarVolumes must be positive and finite (zero volume at "
                                + i + " is a data gap, not infinite illiquidity)");
            }
            sum += Math.abs(returns[i]) / dollarVolumes[i];
        }
        return sum / returns.length;
    }

    private static double square(double x) {
        return x * x;
    }

    private static void requirePositiveFinite(double[] a) {
        for (double x : a) {
            if (!(x > 0) || x == Double.POSITIVE_INFINITY) {
                throw new IllegalArgumentException("prices must be positive and finite");
            }
        }
    }
}
