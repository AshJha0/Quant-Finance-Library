package com.quantfinlib.trading;

/**
 * Avellaneda-Stoikov (2008) optimal market-making quotes — the principled
 * version of the inventory skew {@link HftQuoter} applies heuristically.
 * Two closed-form answers from one utility-maximization problem:
 *
 * <ol>
 *   <li><b>Where is MY mid?</b> The reservation price shades the market
 *       mid against your inventory:
 *       {@code r = mid − q·γ·σ²·τ}. Long inventory (q &gt; 0) pushes both
 *       quotes down — you want to sell, so you make selling attractive and
 *       buying not. The shade grows with risk aversion (γ), variance (σ²)
 *       and remaining horizon (τ): a big position in a wild market you
 *       must carry for hours is worth shading hard;</li>
 *   <li><b>How wide?</b> The optimal total spread
 *       {@code δ = γ·σ²·τ + (2/γ)·ln(1 + γ/κ)} balances the volatility
 *       cost of holding inventory against the fill-rate cost of quoting
 *       wide, where κ is the order-arrival decay (how fast fill intensity
 *       drops as you quote away from the touch — bigger κ = thicker flow
 *       near the mid = quote tighter). As γ → 0 the spread collapses to
 *       the pure liquidity floor 2/κ.</li>
 * </ol>
 *
 * <p><b>Units contract</b> (same discipline as
 * {@code execution.BenchmarkExecutor.MarketState}):</p>
 * <ul>
 *   <li>{@code priceVariancePerSecond} — variance of the PRICE per second,
 *       not the return: from the signal engine,
 *       {@code Math.pow(mid * sig.volPerSqrtSecond(sym), 2)}. NaN or
 *       negative reads as 0 (the inventory term disables; the liquidity
 *       floor keeps quoting sane);</li>
 *   <li>{@code horizonSeconds} — time to the moment inventory must be
 *       flat (the close, the fixing). Continuous 24/5 FX has no terminal
 *       time: use a fixed risk horizon (how long you are willing to sit
 *       on a position) — the standard practitioner reading of τ;</li>
 *   <li>{@code inventory} — signed position in UNITS OF THE INSTRUMENT
 *       the mid prices (shares of the stock, base-currency units of the
 *       pair). This is dimensional, not stylistic: the shade
 *       {@code q·γ·σ²·τ} is in price units only when q counts the same
 *       thing σ² is quoted per — pass round lots instead of shares and
 *       the shade silently shrinks 100× while the spread term (which
 *       never sees q) is unchanged, gutting the skew that is the model's
 *       point. Calibrate γ with the inventory unit fixed first.</li>
 * </ul>
 *
 * <p>Wiring into the fast lane: compute {@code reservationPrice − mid} as
 * the inventory skew and {@link #optimalHalfSpread} as the half-spread,
 * and feed both to {@link HftQuoter}'s skew/spread inputs — the model
 * decides, the quoter executes. Pure static-shape math on primitives:
 * zero allocation, safe on any thread. Like every model here, γ and κ
 * deserve calibration against your own fill data before the output is
 * trusted with size.</p>
 */
public final class AvellanedaStoikov {

    private final double gamma;
    private final double kappa;
    private final double liquidityHalfSpread;   // (1/γ)·ln(1+γ/κ), precomputed

    /**
     * @param gamma risk aversion, e.g. 0.1 (bigger = shade and widen more)
     * @param kappa fill-intensity decay per unit distance from the touch,
     *              e.g. 1.5 (bigger = flow concentrates at the mid)
     */
    public AvellanedaStoikov(double gamma, double kappa) {
        if (gamma <= 0 || kappa <= 0) {
            throw new IllegalArgumentException("need gamma > 0 and kappa > 0");
        }
        this.gamma = gamma;
        this.kappa = kappa;
        // log1p keeps the liquidity floor exact as gamma -> 0 (log(1+x)
        // rounds to 0 below x ~ 1e-16; log1p does not).
        this.liquidityHalfSpread = Math.log1p(gamma / kappa) / gamma;
    }

    /**
     * The inventory-shaded fair value: {@code mid − inventory·γ·σ²·τ}.
     * Flat inventory or a dead/garbage variance returns the mid itself.
     */
    public double reservationPrice(double mid, double inventory,
                                   double priceVariancePerSecond, double horizonSeconds) {
        return mid - inventory * gamma * varianceTerm(priceVariancePerSecond, horizonSeconds);
    }

    /**
     * Half of the optimal total spread:
     * {@code (γ·σ²·τ)/2 + (1/γ)·ln(1 + γ/κ)}. Never below the liquidity
     * floor — even a becalmed market pays for immediacy.
     */
    public double optimalHalfSpread(double priceVariancePerSecond, double horizonSeconds) {
        return 0.5 * gamma * varianceTerm(priceVariancePerSecond, horizonSeconds)
                + liquidityHalfSpread;
    }

    /** The bid to quote: reservation price minus the optimal half-spread. */
    public double bidQuote(double mid, double inventory,
                           double priceVariancePerSecond, double horizonSeconds) {
        return reservationPrice(mid, inventory, priceVariancePerSecond, horizonSeconds)
                - optimalHalfSpread(priceVariancePerSecond, horizonSeconds);
    }

    /** The ask to quote: reservation price plus the optimal half-spread. */
    public double askQuote(double mid, double inventory,
                           double priceVariancePerSecond, double horizonSeconds) {
        return reservationPrice(mid, inventory, priceVariancePerSecond, horizonSeconds)
                + optimalHalfSpread(priceVariancePerSecond, horizonSeconds);
    }

    public double gamma() {
        return gamma;
    }

    public double kappa() {
        return kappa;
    }

    /** σ²·τ with the gap discipline: non-finite or negative inputs read as 0. */
    private static double varianceTerm(double priceVariancePerSecond, double horizonSeconds) {
        if (!(priceVariancePerSecond > 0) || !(horizonSeconds > 0)
                || priceVariancePerSecond == Double.POSITIVE_INFINITY
                || horizonSeconds == Double.POSITIVE_INFINITY) {
            return 0;                      // !(x > 0) also catches NaN
        }
        return priceVariancePerSecond * horizonSeconds;
    }
}
