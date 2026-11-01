package com.quantfinlib.hedging;

/**
 * Whalley-Wilmott optimal hedge bands — the answer to the question
 * every band hedger otherwise guesses: how wide should the no-trade
 * band around delta be? Hedging continuously is bankruptcy by
 * transaction costs; hedging never is a naked option. The asymptotic
 * optimum (Whalley &amp; Wilmott 1997, small proportional costs) puts the
 * half-width at
 *
 * <pre>  band = ( (3/2) · e^{−r(T−t)} · k · S · Γ² / λ )^{1/3}</pre>
 *
 * (Whalley and Wilmott 1997, Mathematical Finance 7(3) 307-324; the
 * discount factor appears because costs paid today are compared with a
 * terminal-wealth utility). Units: {@code k} is a fraction of traded
 * notional, {@code S} and {@code Γ} are per share, and {@code λ} is the
 * exponential-utility risk aversion in 1/currency (utility
 * {@code −e^{−λ·W}}), so {@code λ·S} is dimensionless. A λ of 1 means a one
 * currency unit change in wealth is already material; for a book measured
 * in dollars a realistic λ is of order 1/(capital at risk), e.g. 1e-6.
 *
 * — wider when trading is expensive (k, the proportional cost) and
 * when gamma churns the delta (Γ²), narrower when you are more risk
 * averse (λ). The cube root is the interesting part: costs must move
 * by 8x to move the band by 2x, which is why band width is remarkably
 * stable across venues.
 *
 * <p>The policy is as important as the width: when the hedge drifts
 * outside the band, trade back to the nearest edge, not to the center
 * — hedging to delta itself throws away the band's whole point (you
 * would pay the spread again on the next tick's drift). Zero gamma
 * degenerates cleanly: zero band, always hedge exactly to delta.
 *
 * <p>Pairs with the band executors: {@code trading.AutoHedger} and
 * {@code crb.CrbAutoHedger} take a band as configuration — this class
 * is where that number should come from. Asymptotic result: for very
 * large costs or tiny risk aversion the expansion degrades (stated,
 * not hidden). Static, deterministic, research lane.</p>
 */
public final class WhalleyWilmott {

    /** The rebalancing decision: trade (to the nearest edge) or hold. */
    public record Action(boolean trade, double targetHedge) {
    }

    private WhalleyWilmott() {
    }

    /**
     * The optimal no-trade half-width around delta with zero discounting
     * ({@code r·(T−t) = 0}); see {@link #bandHalfWidth(double, double, double,
     * double, double, double)}.
     */
    public static double bandHalfWidth(double spot, double gamma, double costRate,
                                       double riskAversion) {
        return bandHalfWidth(spot, gamma, costRate, riskAversion, 0, 0);
    }

    /**
     * The optimal no-trade half-width around delta, in shares.
     *
     * @param spot          underlying price, &gt; 0
     * @param gamma         book gamma per share (signed; only |Γ| matters), finite
     * @param costRate      proportional transaction cost k (e.g. 0.0005
     *                      = 5 bps), &gt; 0
     * @param riskAversion  λ &gt; 0 in 1/currency (bigger = tighter hedging)
     * @param rate          continuously compounded rate r, finite
     * @param timeToExpiry  T − t in years, &ge; 0
     */
    public static double bandHalfWidth(double spot, double gamma, double costRate,
                                       double riskAversion, double rate, double timeToExpiry) {
        if (!(spot > 0) || spot == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("spot must be positive and finite");
        }
        if (!Double.isFinite(gamma)) {
            throw new IllegalArgumentException("gamma must be finite");
        }
        if (!(costRate > 0) || costRate == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("costRate must be positive and finite");
        }
        if (!(riskAversion > 0) || riskAversion == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("riskAversion must be positive and finite");
        }
        if (!Double.isFinite(rate) || !(timeToExpiry >= 0) || timeToExpiry == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("rate must be finite and timeToExpiry >= 0");
        }
        return Math.cbrt(1.5 * Math.exp(-rate * timeToExpiry) * costRate * spot * gamma * gamma
                / riskAversion);
    }

    /**
     * The hedge-to-the-edge policy: hold inside the band, trade to the
     * nearest edge outside it.
     *
     * @param currentHedge the position currently held against the book
     * @param delta        the book's current delta (the band's center)
     * @param bandHalfWidth from {@link #bandHalfWidth}, ≥ 0
     */
    public static Action rebalance(double currentHedge, double delta, double bandHalfWidth) {
        if (!Double.isFinite(currentHedge) || !Double.isFinite(delta)) {
            throw new IllegalArgumentException("hedge and delta must be finite");
        }
        if (!(bandHalfWidth >= 0) || bandHalfWidth == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("bandHalfWidth must be >= 0 and finite");
        }
        if (currentHedge < delta - bandHalfWidth) {
            return new Action(true, delta - bandHalfWidth);
        }
        if (currentHedge > delta + bandHalfWidth) {
            return new Action(true, delta + bandHalfWidth);
        }
        return new Action(false, currentHedge);
    }
}
