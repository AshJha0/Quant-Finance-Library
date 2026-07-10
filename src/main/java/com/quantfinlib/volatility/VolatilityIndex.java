package com.quantfinlib.volatility;

/**
 * A VIX-style MARKET volatility index — the "fear gauge": the market's
 * own 30-day volatility expectation, read model-free out of an option
 * chain. No pricing model is assumed; the index is the variance-swap
 * replication (Carr-Madan / CBOE methodology, single expiry):
 *
 * <pre>  σ² = (2/T)·Σᵢ (ΔKᵢ/Kᵢ²)·e^{rT}·Q(Kᵢ)  −  (1/T)·(F/K₀ − 1)²</pre>
 *
 * where Q(K) is the OUT-OF-THE-MONEY option mid at strike K (puts below
 * the forward, calls above, the put/call average at the pivot K₀ — the
 * highest strike at or below F), ΔK the half-distance between
 * neighboring strikes, and the last term corrects for K₀ ≠ F.
 *
 * <p>Why OTM options? Each strike's option contributes exactly the
 * 1/K² slice needed to build a constant-dollar-gamma payoff — a
 * position whose P&amp;L IS realized variance. The market prices that
 * portfolio, so the portfolio's price reveals the market's variance
 * expectation, whatever model anyone used. That is why a vol SMILE
 * raises the index above ATM implied vol: the wings carry real premium
 * and the replication weights them in.
 *
 * <p><b>Honesty notes:</b> single-expiry (the CBOE interpolates two
 * expiries to exactly 30 days — supply the chain nearest your target
 * tenor, or compute two indices and interpolate variance in time);
 * truncation bias: strikes should span several σ√T or the index reads
 * LOW (the tails you cannot see are variance you do not count).
 * Styled after the methodology, not certified. Research lane, static,
 * deterministic.</p>
 */
public final class VolatilityIndex {

    private VolatilityIndex() {
    }

    /**
     * The index (annualized volatility, e.g. 0.20 = "a VIX of 20") from
     * one expiry's chain.
     *
     * @param strikes  ascending strikes, ≥ 3, all &gt; 0
     * @param putMids  put mid prices per strike, ≥ 0, finite
     * @param callMids call mid prices per strike, ≥ 0, finite
     * @param forward  the forward F for this expiry, strictly inside
     *                 (strikes[0], strikes[last]) — an index built on
     *                 extrapolation would be an opinion, not a
     *                 measurement
     * @param rate     continuously-compounded rate to expiry
     * @param tYears   time to expiry, &gt; 0
     */
    public static double index(double[] strikes, double[] putMids, double[] callMids,
                               double forward, double rate, double tYears) {
        int n = strikes.length;
        if (n < 3 || putMids.length != n || callMids.length != n) {
            throw new IllegalArgumentException("need >= 3 aligned strikes/puts/calls");
        }
        if (!(tYears > 0) || tYears == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("tYears must be positive and finite");
        }
        if (!Double.isFinite(rate)) {
            throw new IllegalArgumentException("rate must be finite");
        }
        double prev = 0;
        for (int i = 0; i < n; i++) {
            if (!(strikes[i] > prev) || strikes[i] == Double.POSITIVE_INFINITY) {
                throw new IllegalArgumentException(
                        "strikes must be ascending, positive and finite");
            }
            if (!(putMids[i] >= 0) || putMids[i] == Double.POSITIVE_INFINITY
                    || !(callMids[i] >= 0) || callMids[i] == Double.POSITIVE_INFINITY) {
                throw new IllegalArgumentException("option mids must be >= 0 and finite");
            }
            prev = strikes[i];
        }
        if (!(forward > strikes[0] && forward < strikes[n - 1])) {
            throw new IllegalArgumentException("forward " + forward
                    + " must sit strictly inside the strike range — the index "
                    + "cannot be measured from extrapolation");
        }

        // K0: highest strike at or below the forward.
        int pivot = 0;
        for (int i = 0; i < n; i++) {
            if (strikes[i] <= forward) {
                pivot = i;
            }
        }
        double df = Math.exp(rate * tYears);
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double dk = i == 0 ? strikes[1] - strikes[0]
                    : i == n - 1 ? strikes[n - 1] - strikes[n - 2]
                    : (strikes[i + 1] - strikes[i - 1]) / 2;
            double q = i < pivot ? putMids[i]
                    : i > pivot ? callMids[i]
                    : (putMids[i] + callMids[i]) / 2;    // the K0 straddle average
            sum += dk / (strikes[i] * strikes[i]) * df * q;
        }
        double k0 = strikes[pivot];
        double variance = (2 / tYears) * sum
                - (1 / tYears) * Math.pow(forward / k0 - 1, 2);
        if (!(variance > 0)) {
            throw new IllegalArgumentException("chain implies non-positive variance ("
                    + variance + ") — the quotes are inconsistent");
        }
        return Math.sqrt(variance);
    }
}
