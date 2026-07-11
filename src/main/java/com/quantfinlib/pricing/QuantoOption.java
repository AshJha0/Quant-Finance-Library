package com.quantfinlib.pricing;

import com.quantfinlib.pricing.BlackScholes.OptionType;

/**
 * QUANTO adjustment — for payoffs on a foreign asset settled in domestic
 * currency at a FIXED conversion rate (a Nikkei option paying in USD at
 * 1:1). The buyer bears no FX risk, but the HEDGER does: the delta hedge
 * lives in the asset's own currency, so the hedge P&amp;L converts at a
 * floating rate that is CORRELATED with the asset. That correlation has a
 * price, and it shows up as a drift correction:
 *
 * <pre>  F_quanto = S · e^{(r_dom − q − ρ·σ_S·σ_FX)·T}</pre>
 *
 * <p>Sign intuition (the interview question): ρ here is the correlation
 * between the asset and the FX rate quoted as DOMESTIC PER FOREIGN.
 * Positive ρ means the asset rallies exactly when the foreign currency
 * strengthens — the hedger's foreign-currency losses on the short hedge
 * convert at a worse rate, a systematic drag they charge for, so the
 * quanto forward is LOWER than the vanilla forward. The vol of the quanto
 * payoff stays the asset's own σ_S: only the drift moves.</p>
 *
 * <p>Implementation is honest about being a change of drift: pricing
 * delegates to {@link BlackScholes} with the carry bumped by
 * {@code ρ·σ_S·σ_FX} — one line of new math, every Greek and edge case
 * inherited from the tested vanilla pricer. Research lane.</p>
 */
public final class QuantoOption {

    private QuantoOption() {
    }

    /** The quanto-adjusted forward (domestic-settled, fixed conversion). */
    public static double quantoForward(double spot, double domesticRate, double assetYield,
                                       double assetVol, double fxVol, double rho,
                                       double timeYears) {
        validate(spot, assetVol, fxVol, rho, domesticRate, assetYield);
        if (!(timeYears >= 0) || timeYears == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("timeYears must be >= 0 and finite");
        }
        return spot * Math.exp((domesticRate - assetYield - rho * assetVol * fxVol) * timeYears);
    }

    /**
     * Quanto vanilla priced in domestic currency per unit of the fixed
     * conversion rate: Black-Scholes with the carry shifted by
     * {@code ρ·σ_S·σ_FX}.
     */
    public static double price(OptionType type, double spot, double strike,
                               double domesticRate, double assetYield,
                               double assetVol, double fxVol, double rho, double timeYears) {
        validate(spot, assetVol, fxVol, rho, domesticRate, assetYield);
        if (!(strike > 0) || strike == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("strike must be positive and finite, got " + strike);
        }
        return BlackScholes.price(type, spot, strike, domesticRate,
                assetYield + rho * assetVol * fxVol, assetVol, timeYears);
    }

    private static void validate(double spot, double assetVol, double fxVol, double rho,
                                 double domesticRate, double assetYield) {
        if (!(spot > 0) || spot == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("spot must be positive and finite, got " + spot);
        }
        if (!(assetVol >= 0) || assetVol == Double.POSITIVE_INFINITY
                || !(fxVol >= 0) || fxVol == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("vols must be >= 0 and finite");
        }
        if (!(rho >= -1) || !(rho <= 1)) {
            throw new IllegalArgumentException("rho must be in [-1, 1], got " + rho);
        }
        if (!Double.isFinite(domesticRate) || !Double.isFinite(assetYield)) {
            throw new IllegalArgumentException("rates must be finite");
        }
    }
}
