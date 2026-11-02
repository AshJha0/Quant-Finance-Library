package com.quantfinlib.pricing;

import com.quantfinlib.pricing.BlackScholes.OptionType;

/**
 * Quanto vanilla: a payoff on a foreign asset S (quoted in foreign currency)
 * paid in domestic currency at a fixed conversion rate, for example a Nikkei
 * call paying {@code max(S_T - K, 0)} US dollars per index point.
 *
 * <p>Model (Hull, <i>Options, Futures, and Other Derivatives</i>, the section
 * on quantos in the chapter "Convexity, timing, and quanto adjustments";
 * Wystup, <i>FX Options and Structured Products</i>, 2006, section 2.6). S and
 * the FX rate X (domestic per one foreign) follow correlated geometric
 * Brownian motions with vols σ_S, σ_X and correlation ρ. Under the domestic
 * risk-neutral measure S drifts at r_f − q − ρσ_Sσ_X, so</p>
 *
 * <pre>  F_quanto = S · exp((r_f − q − ρ·σ_S·σ_X)·T)
 *   V = e^{−r_d T} · Black76(F_quanto, K, σ_S, T)</pre>
 *
 * <p>The option is discounted at the domestic rate r_d; the foreign rate r_f
 * only enters the forward. With ρ = 0 the quanto forward is the foreign
 * forward S·e^{(r_f−q)T}, not S·e^{(r_d−q)T}. Positive ρ lowers the forward:
 * the hedger's short foreign-currency position loses exactly when the
 * foreign currency is strong.</p>
 *
 * <p>Pricing delegates to {@link BlackScholes} with rate r_d and carry
 * {@code q + (r_d − r_f) + ρσ_Sσ_X}, which reproduces the forward above.</p>
 */
public final class QuantoOption {

    private QuantoOption() {
    }

    /**
     * Quanto forward of the foreign asset under the domestic measure, in
     * foreign-currency units of S.
     *
     * @param foreignRate continuously compounded rate of the asset's currency
     * @param assetYield  continuous dividend yield of the asset
     * @param rho         correlation of the asset with X = domestic per foreign
     */
    public static double quantoForward(double spot, double foreignRate, double assetYield,
                                       double assetVol, double fxVol, double rho,
                                       double timeYears) {
        validate(spot, assetVol, fxVol, rho, 0.0, foreignRate, assetYield);
        if (!(timeYears >= 0) || timeYears == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("timeYears must be >= 0 and finite");
        }
        return spot * Math.exp((foreignRate - assetYield - rho * assetVol * fxVol) * timeYears);
    }

    /**
     * Quanto vanilla price in domestic currency per unit of the fixed
     * conversion rate.
     *
     * @param domesticRate continuously compounded rate of the payment currency (discounting)
     * @param foreignRate  continuously compounded rate of the asset's currency (drift)
     */
    public static double price(OptionType type, double spot, double strike,
                               double domesticRate, double foreignRate, double assetYield,
                               double assetVol, double fxVol, double rho, double timeYears) {
        validate(spot, assetVol, fxVol, rho, domesticRate, foreignRate, assetYield);
        if (!(strike > 0) || strike == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("strike must be positive and finite, got " + strike);
        }
        double carry = assetYield + (domesticRate - foreignRate) + rho * assetVol * fxVol;
        return BlackScholes.price(type, spot, strike, domesticRate, carry, assetVol, timeYears);
    }

    private static void validate(double spot, double assetVol, double fxVol, double rho,
                                 double domesticRate, double foreignRate, double assetYield) {
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
        if (!Double.isFinite(domesticRate) || !Double.isFinite(foreignRate)
                || !Double.isFinite(assetYield)) {
            throw new IllegalArgumentException("rates must be finite");
        }
    }
}
