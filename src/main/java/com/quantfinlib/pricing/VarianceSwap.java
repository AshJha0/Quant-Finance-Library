package com.quantfinlib.pricing;

import com.quantfinlib.volatility.VolatilityIndex;

/**
 * VARIANCE SWAP analytics — the cleanest pure-volatility trade there is:
 * at expiry the swap pays {@code notional × (realized variance − strike)},
 * no delta, no path-dependent barriers, no vega decay games. The
 * remarkable fact (Demeterfi-Derman-Kamal-Zou 1999) is that its fair
 * strike is MODEL-FREE: a static portfolio of OTM options weighted
 * {@code 1/K²} replicates the log contract, so the strike is readable off
 * the option chain with no volatility model at all — the same integral a
 * VIX-style index computes. {@link #fairVariance} therefore delegates to
 * {@link VolatilityIndex} and squares it: a VIX of 20 IS a variance-swap
 * strike of 0.04. One number, two names.
 *
 * <p>The two quantities a desk actually books:</p>
 * <ul>
 *   <li><b>Variance vs vega notional</b> — dealers quote in VEGA (P&amp;L
 *       per vol point) but settle in VARIANCE units; the bridge is
 *       {@code varianceNotional = vegaNotional / (2·K_vol)}, the
 *       linearization of variance in vol at the strike. Get this wrong
 *       and every P&amp;L number is off by a factor of 2·K_vol.</li>
 *   <li><b>Mark-to-market of a seasoned swap</b> — variance is ADDITIVE
 *       in time, so a swap part-way through its life is just realized
 *       variance so far (locked in) blended with the fair strike for the
 *       remaining leg, discounted:
 *       {@code MTM = e^{-r·(T-t)} · [ (t/T)·realized + ((T-t)/T)·K_rem − K_0 ]}
 *       per unit of variance notional. No model here either — that is
 *       why variance swaps mark cleanly and volatility swaps (square
 *       root of this payoff) do not.</li>
 * </ul>
 *
 * <p>Conventions, stated: variance in annualized decimal² (0.04 = 20
 * vol), time in years, realized variance supplied by the caller (use
 * {@code volatility.HarRv}/realized estimators — this class does not
 * choose your sampling convention for you). The replication carries the
 * discretization bias documented on {@link VolatilityIndex} (~+5e-4 in
 * variance for a coarse chain) and, like all 1/K² replication, prices
 * CONTINUOUS variance — jump risk is why dealers cap payoffs in
 * practice; the cap is not modeled here, stated. Research lane.</p>
 */
public final class VarianceSwap {

    private VarianceSwap() {
    }

    /**
     * Model-free fair strike (annualized VARIANCE, e.g. 0.04) replicated
     * from one expiry's option chain — parameters exactly as
     * {@link VolatilityIndex#index}.
     */
    public static double fairVariance(double[] strikes, double[] putMids, double[] callMids,
                                      double forward, double rate, double tYears) {
        double vol = VolatilityIndex.index(strikes, putMids, callMids, forward, rate, tYears);
        return vol * vol;
    }

    /**
     * VOLATILITY swap fair strike via the Brockhaus-Long convexity
     * correction: {@code E[√V] ≈ √E[V] − Var(V) / (8·E[V]^{3/2})}. A vol
     * swap strike is always BELOW the square root of the variance strike
     * (Jensen: √ is concave), and by how much depends on the variance of
     * variance — which is a model input, not chain-readable; that is
     * exactly why vol swaps are not model-free while variance swaps are.
     *
     * @param fairVariance       E[V], the variance-swap strike, &gt; 0
     * @param varianceOfVariance Var(V) under your vol-of-vol model, &ge; 0
     */
    public static double volSwapStrike(double fairVariance, double varianceOfVariance) {
        if (!(fairVariance > 0) || fairVariance == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("fairVariance must be positive and finite");
        }
        if (!(varianceOfVariance >= 0) || varianceOfVariance == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("varianceOfVariance must be >= 0 and finite");
        }
        return Math.sqrt(fairVariance)
                - varianceOfVariance / (8 * Math.pow(fairVariance, 1.5));
    }

    /**
     * Variance notional from a vega-notional quote:
     * {@code vegaNotional / (2 · strikeVol)}.
     *
     * @param vegaNotional P&amp;L per 1.00 of volatility (per "100 vol points")
     * @param strikeVol    the strike in VOL terms (0.20, not 0.04), &gt; 0
     */
    public static double varianceNotional(double vegaNotional, double strikeVol) {
        if (!(strikeVol > 0) || strikeVol == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("strikeVol must be positive and finite, got " + strikeVol);
        }
        if (!Double.isFinite(vegaNotional)) {
            throw new IllegalArgumentException("vegaNotional must be finite");
        }
        return vegaNotional / (2 * strikeVol);
    }

    /**
     * Mark-to-market of a seasoned variance swap per unit of variance
     * notional (multiply by {@link #varianceNotional} for money).
     *
     * @param strikeVariance   original strike K₀ (variance units, &gt; 0)
     * @param realizedVariance annualized variance realized over [0, t], &ge; 0
     * @param remainingFair    current fair strike for [t, T] (variance), &ge; 0
     * @param tElapsedYears    elapsed time t, &ge; 0
     * @param tTotalYears      total life T, &gt; 0, &ge; t
     * @param rate             cc discount rate to expiry
     */
    public static double markToMarket(double strikeVariance, double realizedVariance,
                                      double remainingFair, double tElapsedYears,
                                      double tTotalYears, double rate) {
        if (!(strikeVariance > 0) || strikeVariance == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("strikeVariance must be positive and finite");
        }
        if (!(realizedVariance >= 0) || realizedVariance == Double.POSITIVE_INFINITY
                || !(remainingFair >= 0) || remainingFair == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("variances must be >= 0 and finite");
        }
        if (!(tTotalYears > 0) || tTotalYears == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("tTotalYears must be positive and finite");
        }
        if (!(tElapsedYears >= 0) || tElapsedYears > tTotalYears) {
            throw new IllegalArgumentException(
                    "tElapsedYears must be in [0, " + tTotalYears + "], got " + tElapsedYears);
        }
        if (!Double.isFinite(rate)) {
            throw new IllegalArgumentException("rate must be finite");
        }
        double weightElapsed = tElapsedYears / tTotalYears;
        double expected = weightElapsed * realizedVariance + (1 - weightElapsed) * remainingFair;
        return Math.exp(-rate * (tTotalYears - tElapsedYears)) * (expected - strikeVariance);
    }
}
