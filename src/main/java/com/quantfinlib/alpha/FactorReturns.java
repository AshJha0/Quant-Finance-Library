package com.quantfinlib.alpha;

/**
 * Factor return streams built from the library's own factors, the input
 * {@link AlphaReport#attribute} regresses a portfolio on.
 *
 * <p>{@link #longShort} is the Fama-French style construction: on each
 * rebalance date sort the active cross-section by score, buy the top
 * quantile and sell the bottom quantile equal-weighted, then hold both legs
 * buy-and-hold until the next rebalance (weights drift with prices; no free
 * daily rebalancing). The factor return of bar {@code t} is
 * {@code r_long(t) − r_short(t)}, each leg's return being the change in the
 * leg's value since the previous bar. Returns are terminal-event aware
 * through {@link AlphaContext#returnOver}: a name that delists mid-hold
 * contributes its delisting return and is then held as cash.</p>
 */
public final class FactorReturns {

    private FactorReturns() {
    }

    /**
     * Per-bar long-short factor returns over {@code (t, t+1]} for
     * {@code t = startIndex .. bars−2}, so the result has
     * {@code bars − 1 − startIndex} entries. Bars before the first scored
     * rebalance return 0.
     *
     * @param quantiles      number of buckets (5 = top minus bottom quintile)
     * @param rebalanceEvery bars between re-sorts
     */
    public static double[] longShort(AlphaContext ctx, AlphaFactor factor, int startIndex,
                                     int quantiles, int rebalanceEvery) {
        if (quantiles < 2 || rebalanceEvery < 1 || startIndex < 0
                || startIndex >= ctx.bars() - 1) {
            throw new IllegalArgumentException("need quantiles >= 2, rebalanceEvery >= 1 and "
                    + "startIndex in [0, bars-1)");
        }
        int m = ctx.symbolCount();
        double[] out = new double[ctx.bars() - 1 - startIndex];
        int[] longs = new int[0];
        int[] shorts = new int[0];
        int anchor = -1;
        for (int t = startIndex; t < ctx.bars() - 1; t++) {
            if ((t - startIndex) % rebalanceEvery == 0) {
                double[] s = factor.scores(ctx, t);
                int n = 0;
                for (int i = 0; i < m; i++) {
                    if (!Double.isNaN(s[i])) {
                        n++;
                    }
                }
                if (n >= quantiles) {
                    Integer[] order = new Integer[n];
                    int k = 0;
                    for (int i = 0; i < m; i++) {
                        if (!Double.isNaN(s[i])) {
                            order[k++] = i;
                        }
                    }
                    java.util.Arrays.sort(order, (a, b) -> Double.compare(s[a], s[b]));
                    int per = n / quantiles;
                    shorts = new int[per];
                    longs = new int[per];
                    for (int j = 0; j < per; j++) {
                        shorts[j] = order[j];
                        longs[j] = order[n - per + j];
                    }
                    anchor = t;
                }
            }
            if (anchor < 0) {
                continue;
            }
            out[t - startIndex] = legReturn(ctx, longs, anchor, t)
                    - legReturn(ctx, shorts, anchor, t);
        }
        return out;
    }

    /** Return over (t, t+1] of an equal-weighted buy-and-hold leg formed at {@code anchor}. */
    private static double legReturn(AlphaContext ctx, int[] names, int anchor, int t) {
        if (names.length == 0) {
            return 0;
        }
        double before = 0;
        double after = 0;
        for (int i : names) {
            before += 1 + (t == anchor ? 0 : ctx.returnOver(i, anchor, t));
            after += 1 + ctx.returnOver(i, anchor, t + 1);
        }
        return after / before - 1;
    }
}
