package com.quantfinlib.backtest;

/**
 * When an order decided on bar {@code t} is filled. Shared by every bar
 * engine ({@link Backtester}, {@code backtest.portfolio.PortfolioBacktester},
 * {@code alpha.AlphaBacktester}).
 *
 * <p>Signals in this library are computed from bars up to and including
 * {@code close(t)}. A fill at that same close assumes the order went in
 * before the closing print using a price that did not exist yet, which is
 * not attainable. {@link #NEXT_OPEN} is the default everywhere: decide on
 * the close, trade at the next bar's open. The gap between a {@code CLOSE}
 * run and a {@code NEXT_OPEN} run measures how much of a strategy's edge
 * lives inside one bar of latency; fast mean-reversion signals often lose
 * most of it.</p>
 */
public enum FillPolicy {

    /**
     * Fill at {@code close(t)}, the decision bar's own close. Optimistic:
     * use it to measure the zero-latency upper bound, not to report results.
     */
    CLOSE,

    /** Fill at {@code open(t + 1)}. The default. */
    NEXT_OPEN,

    /**
     * Fill at {@code close(t + 1)}: a market-on-close order submitted one
     * bar after the decision, the usual convention in daily cross-sectional
     * research.
     */
    NEXT_CLOSE;

    /** Bars between the decision bar and the fill bar (0 or 1). */
    public int lagBars() {
        return this == CLOSE ? 0 : 1;
    }
}
