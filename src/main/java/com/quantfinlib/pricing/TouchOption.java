package com.quantfinlib.pricing;

import com.quantfinlib.util.MathUtils;

/**
 * One-touch and no-touch options (pay-at-expiry) under continuously
 * monitored geometric Brownian motion — with the barrier-hitting
 * probability itself exposed, since desks quote one-touches <em>as</em>
 * (roughly) discounted hit probabilities.
 *
 * <p>With log-drift {@code m = r − q − σ²/2} and barrier log-distance
 * {@code h = ln(H/S)}, the reflection principle gives the probability of
 * touching an <b>upper</b> barrier ({@code h > 0}) by expiry:</p>
 *
 * <pre>  P = N((−h + mT)/σ√T) + e^{2mh/σ²} · N((−h − mT)/σ√T)</pre>
 *
 * <p>and symmetrically for a lower barrier. A one-touch paying at expiry is
 * {@code payout·e^{−rT}·P}; a no-touch is the complement. Conventions match
 * {@link BlackScholes}: {@code carry} is the continuous yield (foreign rate
 * for FX).</p>
 *
 * <p>Pay-at-hit one-touches (rebate paid immediately on touch) differ by
 * discounting to the hitting time; the pay-at-expiry form here is the one
 * used in vanna-volga books and matches how {@code BarrierOption} treats
 * rebates (it doesn't — knockouts here carry no rebate).</p>
 */
public final class TouchOption {

    private TouchOption() {
    }

    /**
     * Probability that spot touches {@code barrier} at least once before
     * {@code timeYears}, under GBM with the given rate/carry/vol.
     */
    public static double hitProbability(double spot, double barrier, double rate,
                                        double carry, double vol, double timeYears) {
        validate(spot, barrier, vol, timeYears);
        if (spot == barrier) {
            return 1; // already touching
        }
        if (timeYears == 0) {
            return 0;
        }
        double m = rate - carry - 0.5 * vol * vol;
        double h = Math.log(barrier / spot);
        double sq = vol * Math.sqrt(timeYears);
        double drift = m * timeYears;
        if (h > 0) {
            // Upper barrier: reflect around +h.
            return MathUtils.normCdf((-h + drift) / sq)
                    + Math.exp(2 * m * h / (vol * vol)) * MathUtils.normCdf((-h - drift) / sq);
        }
        // Lower barrier: mirror image.
        return MathUtils.normCdf((h - drift) / sq)
                + Math.exp(2 * m * h / (vol * vol)) * MathUtils.normCdf((h + drift) / sq);
    }

    /** Pays {@code payout} at expiry if the barrier traded at any point. */
    public static double oneTouch(double spot, double barrier, double rate, double carry,
                                  double vol, double timeYears, double payout) {
        return payout * Math.exp(-rate * timeYears)
                * hitProbability(spot, barrier, rate, carry, vol, timeYears);
    }

    /** Pays {@code payout} at expiry if the barrier never traded. */
    public static double noTouch(double spot, double barrier, double rate, double carry,
                                 double vol, double timeYears, double payout) {
        return payout * Math.exp(-rate * timeYears)
                * (1 - hitProbability(spot, barrier, rate, carry, vol, timeYears));
    }

    private static void validate(double spot, double barrier, double vol, double t) {
        if (spot <= 0 || barrier <= 0 || vol <= 0 || t < 0) {
            throw new IllegalArgumentException(
                    "spot, barrier, vol must be > 0 and timeYears >= 0");
        }
    }
}
