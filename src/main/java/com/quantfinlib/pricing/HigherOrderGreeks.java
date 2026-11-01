package com.quantfinlib.pricing;

import com.quantfinlib.util.MathUtils;

/**
 * The second-order Greeks a vol book actually hedges with — analytic
 * Black-Scholes forms, the risk-side complement to
 * {@link BlackScholes#greeks}:
 *
 * <ul>
 *   <li><b>Vanna</b> {@code ∂²V/∂S∂σ} — how delta drifts when vol moves
 *       (equivalently, how vega drifts when spot moves). The skew-hedging
 *       Greek: a delta-hedged book with vanna is not hedged through a
 *       spot-vol move, which is how equity markets actually move
 *       (down-spot, up-vol);</li>
 *   <li><b>Volga</b> {@code ∂²V/∂σ²} (vomma) — vega convexity: a
 *       vega-hedged book with volga re-exposes itself as soon as vol
 *       moves. Vanna and volga are the two Greeks the
 *       {@link VannaVolga} pricing method charges the smile for;</li>
 *   <li><b>Cross-gamma</b> — for a two-asset position, the P&amp;L term
 *       {@code ∂²V/∂S₁∂S₂} that plain per-asset gammas miss; supplied
 *       here for the exchange-option/basket case under a correlation
 *       (the general multi-asset case needs the full Hessian of your
 *       specific payoff).</li>
 * </ul>
 *
 * <p>Same conventions as {@code BlackScholes}: {@code carry} is the
 * continuous yield q (dividend yield; the foreign rate for FX), so the
 * spot prefix is {@code e^{−qT}} and the drift is {@code r − q} —
 * exactly the parameters you already pass to
 * {@code BlackScholes.delta/vega}, and the tests pin these formulas as
 * finite differences of those. Vol per √year, all static and
 * allocation-free. Vanna/volga are identical for calls and puts
 * (put-call parity kills the sign difference in the second order).</p>
 */
public final class HigherOrderGreeks {

    private HigherOrderGreeks() {
    }

    /** Vanna ∂²V/∂S∂σ: per 1.00 spot × 1.00 vol. Same for calls and puts. */
    public static double vanna(double spot, double strike, double rate, double carry,
                               double vol, double timeYears) {
        if (timeYears <= 0 || vol <= 0 || spot <= 0) {
            return 0;
        }
        double sqrtT = Math.sqrt(timeYears);
        double d1 = d1(spot, strike, rate, carry, vol, timeYears);
        double d2 = d1 - vol * sqrtT;
        return -Math.exp(-carry * timeYears) * MathUtils.normPdf(d1) * d2 / vol;
    }

    /** Volga (vomma) ∂²V/∂σ²: vega convexity. Same for calls and puts. */
    public static double volga(double spot, double strike, double rate, double carry,
                               double vol, double timeYears) {
        if (timeYears <= 0 || vol <= 0 || spot <= 0) {
            return 0;
        }
        double sqrtT = Math.sqrt(timeYears);
        double d1 = d1(spot, strike, rate, carry, vol, timeYears);
        double d2 = d1 - vol * sqrtT;
        double vega = spot * Math.exp(-carry * timeYears)
                * MathUtils.normPdf(d1) * sqrtT;
        return vega * d1 * d2 / vol;
    }

    /**
     * Cross-gamma of a Margrabe exchange option (the right to exchange
     * asset 2 for asset 1) — the canonical two-asset second-order term:
     * {@code ∂²V/∂S₁∂S₂ = −φ(d₁)/(S₂·σ̂·√T)} where
     * {@code σ̂² = σ₁² + σ₂² − 2ρσ₁σ₂}. Negative: the exchange option
     * loses convexity when the two legs move together. For a generic
     * basket, differentiate your pricer numerically instead — this is
     * the closed form worth having, not a universal answer.
     */
    public static double exchangeCrossGamma(double spot1, double spot2, double vol1,
                                            double vol2, double correlation,
                                            double timeYears) {
        if (timeYears <= 0 || spot1 <= 0 || spot2 <= 0) {
            return 0;
        }
        double sigmaHat = Math.sqrt(Math.max(1e-12,
                vol1 * vol1 + vol2 * vol2 - 2 * correlation * vol1 * vol2));
        double sqrtT = Math.sqrt(timeYears);
        double d1 = (Math.log(spot1 / spot2) + 0.5 * sigmaHat * sigmaHat * timeYears)
                / (sigmaHat * sqrtT);
        return -MathUtils.normPdf(d1) / (spot2 * sigmaHat * sqrtT);
    }

    private static double d1(double spot, double strike, double rate, double carry,
                             double vol, double timeYears) {
        return (Math.log(spot / strike) + (rate - carry + 0.5 * vol * vol) * timeYears)
                / (vol * Math.sqrt(timeYears));
    }
}
