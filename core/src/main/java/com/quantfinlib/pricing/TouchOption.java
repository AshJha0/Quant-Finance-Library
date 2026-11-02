package com.quantfinlib.pricing;

import com.quantfinlib.stats.SpecialFunctions;

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
 * <p>Pay-at-hit one-touches (rebate paid immediately on touch) discount to
 * the hitting time instead: {@link #oneTouchPayAtHit}. Double no-touch and
 * double one-touch (two barriers, pay at expiry) are
 * {@link #doubleNoTouch} and {@link #doubleOneTouch}.</p>
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
            return SpecialFunctions.normCdf((-h + drift) / sq)
                    + Math.exp(2 * m * h / (vol * vol)) * SpecialFunctions.normCdf((-h - drift) / sq);
        }
        // Lower barrier: mirror image.
        return SpecialFunctions.normCdf((h - drift) / sq)
                + Math.exp(2 * m * h / (vol * vol)) * SpecialFunctions.normCdf((h + drift) / sq);
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

    /**
     * One-touch paying {@code payout} at the moment the barrier is first
     * touched (American digital, "rebate at hit"). Closed form of Reiner and
     * Rubinstein (1991, "Breaking down the barriers", Risk 4(8)), as printed in
     * Haug (2007, The Complete Guide to Option Pricing Formulas, 2nd ed., section
     * 4.17.3, rebate term F). With {@code b = rate - carry},
     *
     * <pre>
     *   mu     = (b - vol^2/2) / vol^2
     *   lambda = sqrt(mu^2 + 2 rate / vol^2)
     *   z      = ln(H/S) / (vol sqrt T) + lambda vol sqrt T
     *   price  = payout [ (H/S)^(mu+lambda) N(eta z) + (H/S)^(mu-lambda) N(eta z - 2 eta lambda vol sqrt T) ]
     * </pre>
     *
     * <p>with {@code eta = +1} for a lower barrier ({@code H < S}) and
     * {@code eta = -1} for an upper one. The formula is the Laplace transform
     * {@code E[e^{-rate tau} 1{tau <= T}]} of the first-passage time tau, so
     * {@code rate} discounts from the hitting time, not from expiry. For FX,
     * {@code rate} is the domestic rate and {@code carry} the foreign rate, and
     * the payout is in domestic currency. With {@code rate = 0} it equals
     * {@link #oneTouch} (tested). Requires {@code mu^2 + 2 rate / vol^2 >= 0};
     * a more negative domestic rate has no real lambda and is rejected.</p>
     *
     * @param barrier the touch level; above spot is an up-touch, below a down-touch
     * @return present value in the payout currency; {@code payout} if spot is at the barrier
     */
    public static double oneTouchPayAtHit(double spot, double barrier, double rate,
                                          double carry, double vol, double timeYears,
                                          double payout) {
        validate(spot, barrier, vol, timeYears);
        if (!Double.isFinite(rate) || !Double.isFinite(carry) || !Double.isFinite(payout)
                || !Double.isFinite(vol) || !Double.isFinite(timeYears)) {
            throw new IllegalArgumentException("inputs must be finite");
        }
        if (spot == barrier) {
            return payout;
        }
        if (timeYears == 0) {
            return 0;
        }
        double v2 = vol * vol;
        double mu = (rate - carry - 0.5 * v2) / v2;
        double disc = mu * mu + 2 * rate / v2;
        if (disc < 0) {
            throw new IllegalArgumentException(
                    "mu^2 + 2 rate / vol^2 < 0: no real Laplace exponent for this negative rate");
        }
        double lambda = Math.sqrt(disc);
        double sq = vol * Math.sqrt(timeYears);
        double logHs = Math.log(barrier / spot);
        double eta = barrier < spot ? 1 : -1;
        double z = logHs / sq + lambda * sq;
        double a = Math.exp((mu + lambda) * logHs) * SpecialFunctions.normCdf(eta * z);
        double b = Math.exp((mu - lambda) * logHs)
                * SpecialFunctions.normCdf(eta * z - 2 * eta * lambda * sq);
        return payout * (a + b);
    }

    /**
     * Number of image terms on each side used by {@link #doubleNoTouchProbability}:
     * the series runs over {@code n = -N..N}. Term n is bounded by
     * {@code exp(-2 n^2 l^2 / (vol^2 T))} times a factor of order
     * {@code exp(2 |mu| n l)}, with {@code l = ln(U/L)}; for {@code l / (vol sqrt T) >= 0.1}
     * the omitted tail is below 1e-15 of the payout (checked against the
     * eigenfunction series in the tests).
     */
    public static final int DOUBLE_TOUCH_SERIES_TERMS = 50;

    /**
     * Probability that spot stays strictly inside {@code (lower, upper)} until
     * {@code timeYears} under GBM with continuous monitoring. Image-sum series
     * of Ikeda and Kunitomo (1992, "Pricing options with curved boundaries",
     * Mathematical Finance 2(4)) with flat boundaries, written in log space
     * with {@code x = ln(S/L)}, {@code l = ln(U/L)}, {@code m = rate - carry - vol^2/2},
     * {@code a = m / vol^2}, {@code s = vol sqrt T}:
     *
     * <pre>
     *   P = sum_{n=-N..N} e^{-2 a n l}     [N((l - x + 2nl - mT)/s) - N((2nl - x - mT)/s)]
     *                   - e^{-2 a (x + n l)} [N((l + x + 2nl - mT)/s) - N((x + 2nl - mT)/s)]
     * </pre>
     *
     * <p>with {@code N = } {@link #DOUBLE_TOUCH_SERIES_TERMS}. Returns 0 when
     * spot is on or outside a barrier.</p>
     */
    public static double doubleNoTouchProbability(double spot, double lower, double upper,
                                                  double rate, double carry, double vol,
                                                  double timeYears) {
        validate(spot, lower, vol, timeYears);
        if (!(upper > lower) || !Double.isFinite(upper) || !Double.isFinite(rate)
                || !Double.isFinite(carry) || !Double.isFinite(vol)
                || !Double.isFinite(timeYears)) {
            throw new IllegalArgumentException("need finite inputs and upper > lower > 0");
        }
        if (spot <= lower || spot >= upper) {
            return 0;
        }
        if (timeYears == 0) {
            return 1;
        }
        double m = rate - carry - 0.5 * vol * vol;
        double a = m / (vol * vol);
        double x = Math.log(spot / lower);
        double l = Math.log(upper / lower);
        double s = vol * Math.sqrt(timeYears);
        double mt = m * timeYears;
        double p = 0;
        for (int n = -DOUBLE_TOUCH_SERIES_TERMS; n <= DOUBLE_TOUCH_SERIES_TERMS; n++) {
            double twoNl = 2 * n * l;
            double t1 = SpecialFunctions.normCdf((l - x + twoNl - mt) / s)
                    - SpecialFunctions.normCdf((twoNl - x - mt) / s);
            double t2 = SpecialFunctions.normCdf((l + x + twoNl - mt) / s)
                    - SpecialFunctions.normCdf((x + twoNl - mt) / s);
            // Skip exactly-zero brackets so a huge exponential cannot make 0 * inf = NaN.
            if (t1 != 0) {
                p += Math.exp(-2 * a * n * l) * t1;
            }
            if (t2 != 0) {
                p -= Math.exp(-2 * a * (x + n * l)) * t2;
            }
        }
        if (!Double.isFinite(p)) {
            throw new ArithmeticException("double no-touch series overflowed; drift too large for the band");
        }
        return Math.min(1, Math.max(0, p));
    }

    /**
     * Double no-touch: pays {@code payout} at expiry if spot never touches
     * {@code lower} or {@code upper}. Equals
     * {@code payout e^{-rate T} doubleNoTouchProbability(...)}; {@code rate} is
     * the domestic rate and {@code carry} the foreign rate for FX.
     */
    public static double doubleNoTouch(double spot, double lower, double upper, double rate,
                                       double carry, double vol, double timeYears,
                                       double payout) {
        return payout * Math.exp(-rate * timeYears)
                * doubleNoTouchProbability(spot, lower, upper, rate, carry, vol, timeYears);
    }

    /**
     * Double one-touch paying at expiry: pays {@code payout} if either barrier
     * is touched before expiry. The complement of {@link #doubleNoTouch}.
     */
    public static double doubleOneTouch(double spot, double lower, double upper, double rate,
                                        double carry, double vol, double timeYears,
                                        double payout) {
        return payout * Math.exp(-rate * timeYears)
                * (1 - doubleNoTouchProbability(spot, lower, upper, rate, carry, vol, timeYears));
    }

    private static void validate(double spot, double barrier, double vol, double t) {
        if (spot <= 0 || barrier <= 0 || vol <= 0 || t < 0) {
            throw new IllegalArgumentException(
                    "spot, barrier, vol must be > 0 and timeYears >= 0");
        }
    }
}
