package com.quantfinlib.fx;

import com.quantfinlib.pricing.TouchOption;
import com.quantfinlib.pricing.VannaVolga;

/**
 * Vanna-volga adjusted one-touch (pays at expiry), the FX market
 * convention for smile-consistent touch prices (Wystup 2006, <i>FX Options
 * and Structured Products</i>, section 1.5 on vanna-volga pricing;
 * Bossens, Rayée, Skantzos and Deelstra 2010, "Vanna-volga methods applied
 * to FX derivatives", IJTAF 13(8)).
 *
 * <pre>
 *   OT_VV = OT_BS(σ_atm) + p · Σᵢ xᵢ · [BS(Kᵢ; σᵢ) − BS(Kᵢ; σ_atm)]
 * </pre>
 *
 * <p>where x solves the vanna-volga system ({@link VannaVolga#weightsFor})
 * for the one-touch's own vega, vanna and volga at the ATM vol, and p is the
 * Black-Scholes no-touch (survival) probability at the ATM vol. The
 * survival weight reflects that the hedge is unwound when the barrier
 * trades, and makes the adjustment vanish as spot approaches the barrier.
 * The one-touch Greeks are central finite differences of
 * {@link TouchOption#oneTouch} (steps 1e-4 in vol and 1e-4·S in spot).
 * Rate, carry and expiry are the pillars' own.</p>
 */
public final class VannaVolgaTouch {

    private static final double DV = 1e-4;

    private VannaVolgaTouch() {
    }

    /** Vanna-volga one-touch price with survival-probability weighting. */
    public static double oneTouch(VannaVolga pillars, double spot, double barrier, double payout) {
        validate(pillars, spot, barrier, payout);
        double atm = pillars.vol(1);
        double p = 1 - TouchOption.hitProbability(spot, barrier, pillars.rate(), pillars.carry(), atm,
                pillars.timeYears());
        if (Math.abs(Math.log(spot / barrier)) <= 2e-4) {
            // At (or within one spot bump of) the barrier the touch is
            // certain or nearly so; the Greeks straddle the barrier and p ~ 0.
            return blackScholes(pillars, spot, barrier, payout, atm);
        }
        return blackScholes(pillars, spot, barrier, payout, atm) + p * smileCost(pillars, spot, barrier, payout);
    }

    /**
     * The unweighted vanna-volga smile cost Σ xᵢ · pillar cost for the
     * one-touch (the adjustment before the survival weight).
     */
    public static double smileCost(VannaVolga pillars, double spot, double barrier, double payout) {
        validate(pillars, spot, barrier, payout);
        double atm = pillars.vol(1);
        double hs = 1e-4 * spot;
        double up = blackScholes(pillars, spot, barrier, payout, atm + DV);
        double mid = blackScholes(pillars, spot, barrier, payout, atm);
        double dn = blackScholes(pillars, spot, barrier, payout, atm - DV);
        double vega = (up - dn) / (2 * DV);
        double volga = (up - 2 * mid + dn) / (DV * DV);
        double vanna = (blackScholes(pillars, spot + hs, barrier, payout, atm + DV)
                - blackScholes(pillars, spot + hs, barrier, payout, atm - DV)
                - blackScholes(pillars, spot - hs, barrier, payout, atm + DV)
                + blackScholes(pillars, spot - hs, barrier, payout, atm - DV)) / (4 * hs * DV);
        double[] x = pillars.weightsFor(spot, vega, vanna, volga);
        double cost = 0;
        for (int i = 0; i < 3; i++) {
            cost += x[i] * pillars.pillarSmileCost(spot, i);
        }
        return cost;
    }

    private static double blackScholes(VannaVolga v, double spot, double barrier, double payout, double vol) {
        return TouchOption.oneTouch(spot, barrier, v.rate(), v.carry(), vol, v.timeYears(), payout);
    }

    private static void validate(VannaVolga pillars, double spot, double barrier, double payout) {
        if (pillars == null || !(spot > 0) || !(barrier > 0) || !(payout >= 0) || !Double.isFinite(payout)) {
            throw new IllegalArgumentException("pillars non-null, spot and barrier > 0, payout >= 0");
        }
    }
}
