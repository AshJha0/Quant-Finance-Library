package com.quantfinlib.markets;

/**
 * INDEX construction — the arithmetic behind "the market was up 1%".
 * Three weighting schemes produce three different markets from the same
 * stocks:
 *
 * <ul>
 *   <li><b>cap-weighted</b> (S&amp;P 500 style): weight = float-adjusted
 *       market cap share. Self-rebalancing under price moves (a stock
 *       that doubles doubles its own weight — no trading needed), which
 *       is why cap-weight is the lowest-turnover scheme and the natural
 *       benchmark;</li>
 *   <li><b>price-weighted</b> (Dow style): weight = price share. A $400
 *       stock moves the index 8x as much as a $50 stock regardless of
 *       company size — a historical accident kept alive by
 *       tradition;</li>
 *   <li><b>equal-weighted</b>: constant 1/N. Systematically tilts small
 *       and must TRADE every rebalance to stay equal — the turnover is
 *       the price of the tilt.</li>
 * </ul>
 *
 * <p>The <b>DIVISOR</b> is how a level series survives membership and
 * share changes: {@code level = sum(price * shares * float) / divisor}.
 * When a member is added, dropped or re-floated, the divisor is rescaled
 * so the level is CONTINUOUS through the change —
 * {@code newDivisor = oldDivisor * newAggregate / oldAggregate} — so the
 * index only ever moves for price reasons (pinned by test: a member swap
 * leaves the level unchanged at the instant of the swap).
 * {@code turnover(w1, w2) = 0.5 * sum |w1 - w2|} is the one-way fraction
 * of the portfolio that must trade between two weight vectors — the
 * index-tracking cost driver. Research lane, deterministic.</p>
 */
public final class IndexConstruction {

    private IndexConstruction() {
    }

    /** Float-adjusted cap weights: {@code w_i ∝ price_i * shares_i * float_i}. */
    public static double[] capWeights(double[] prices, double[] shares, double[] floatFactors) {
        int n = validate(prices, shares, floatFactors);
        double[] w = new double[n];
        double total = 0;
        for (int i = 0; i < n; i++) {
            w[i] = prices[i] * shares[i] * floatFactors[i];
            total += w[i];
        }
        return normalize(w, total);
    }

    /** Price weights: {@code w_i ∝ price_i} (the Dow's accident). */
    public static double[] priceWeights(double[] prices) {
        int n = validatePrices(prices);
        double[] w = new double[n];
        double total = 0;
        for (int i = 0; i < n; i++) {
            w[i] = prices[i];
            total += prices[i];
        }
        return normalize(w, total);
    }

    /** Equal weights, 1/N. */
    public static double[] equalWeights(int n) {
        if (n < 1) {
            throw new IllegalArgumentException("need n >= 1, got " + n);
        }
        double[] w = new double[n];
        java.util.Arrays.fill(w, 1.0 / n);
        return w;
    }

    /** Index level from an aggregate and a divisor. */
    public static double level(double[] prices, double[] shares, double[] floatFactors,
                               double divisor) {
        validate(prices, shares, floatFactors);
        if (!(divisor > 0) || divisor == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("divisor must be positive and finite");
        }
        double agg = 0;
        for (int i = 0; i < prices.length; i++) {
            agg += prices[i] * shares[i] * floatFactors[i];
        }
        return agg / divisor;
    }

    /**
     * The rescaled divisor that keeps the level CONTINUOUS through a
     * membership/share/float change: pass the aggregate cap before and
     * after the change (both at the same instant).
     */
    public static double adjustDivisor(double oldDivisor, double oldAggregate,
                                       double newAggregate) {
        if (!(oldDivisor > 0) || !(oldAggregate > 0) || !(newAggregate > 0)) {
            throw new IllegalArgumentException("divisor and aggregates must be positive");
        }
        return oldDivisor * newAggregate / oldAggregate;
    }

    /** One-way turnover between two aligned weight vectors: {@code 0.5 * sum|w1-w2|}. */
    public static double turnover(double[] from, double[] to) {
        if (from.length != to.length) {
            throw new IllegalArgumentException("weight vectors must align");
        }
        double sum = 0;
        for (int i = 0; i < from.length; i++) {
            if (!Double.isFinite(from[i]) || !Double.isFinite(to[i])) {
                throw new IllegalArgumentException("non-finite weight at " + i);
            }
            sum += Math.abs(from[i] - to[i]);
        }
        return 0.5 * sum;
    }

    private static int validate(double[] prices, double[] shares, double[] floatFactors) {
        int n = validatePrices(prices);
        if (shares.length != n || floatFactors.length != n) {
            throw new IllegalArgumentException("prices/shares/floats must align");
        }
        for (int i = 0; i < n; i++) {
            if (!(shares[i] > 0) || shares[i] == Double.POSITIVE_INFINITY) {
                throw new IllegalArgumentException("shares must be positive and finite: " + shares[i]);
            }
            if (!(floatFactors[i] > 0) || floatFactors[i] > 1) {
                throw new IllegalArgumentException(
                        "float factor must be in (0, 1]: " + floatFactors[i]);
            }
        }
        return n;
    }

    private static int validatePrices(double[] prices) {
        if (prices.length == 0) {
            throw new IllegalArgumentException("need at least one constituent");
        }
        for (double p : prices) {
            if (!(p > 0) || p == Double.POSITIVE_INFINITY) {
                throw new IllegalArgumentException("price must be positive and finite: " + p);
            }
        }
        return prices.length;
    }

    private static double[] normalize(double[] w, double total) {
        for (int i = 0; i < w.length; i++) {
            w[i] /= total;
        }
        return w;
    }
}
