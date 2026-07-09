package com.quantfinlib.pricing;

import java.util.Random;

/**
 * Autocallable note pricer — the flagship equity structured product: a
 * note that pays a fat coupon and redeems early ("autocalls") the first
 * observation date the underlier closes at or above the autocall
 * barrier. If it survives to maturity, the principal is protected UNLESS
 * the underlier has fallen through the knock-in barrier, in which case
 * the holder takes the equity loss — the note is, economically, a bond
 * plus sold down-and-in put, funded by the coupons.
 *
 * <p>Structure priced here (single underlier, the classic form):</p>
 * <ul>
 *   <li>Observation dates {@code observationYears[i]}; at each, if
 *       {@code S ≥ autocallBarrier × S₀}: redeem notional + the coupon
 *       for that period, <b>plus any previously missed coupons</b> when
 *       {@code memoryCoupons} is set (the "Phoenix memory" feature);</li>
 *   <li>At each observation where {@code S ≥ couponBarrier × S₀} but
 *       below the autocall barrier: pay the coupon, note continues;</li>
 *   <li>At maturity (the last observation) without autocall: redeem
 *       notional if {@code S_T ≥ knockInBarrier × S₀}, else redeem
 *       {@code notional × S_T/S₀} — the equity loss.</li>
 * </ul>
 *
 * <p><b>Model honesty.</b> Monte Carlo under Black-Scholes GBM with flat
 * volatility and rates — the standard first pricer, NOT a desk-grade
 * one: real autocall books are priced on local/stochastic vol because
 * the knock-in put is deeply smile-sensitive (feed a vol appropriate to
 * the downside strike region, e.g. from {@code VolSurface}, as a
 * first-order correction). European knock-in (observed at maturity
 * only), observation-date monitoring (no continuous barriers), no
 * issuer credit spread — each a documented simplification. Antithetic
 * variates halve the variance; a fixed seed makes every price
 * reproducible and every test exact.</p>
 *
 * <p>Immutable and thread-safe; construction validates, {@link #price}
 * allocates only its path scratch (research lane).</p>
 */
public final class Autocallable {

    private final double notional;
    private final double[] observationYears;
    private final double autocallBarrier;   // fraction of S0
    private final double couponBarrier;     // fraction of S0
    private final double knockInBarrier;    // fraction of S0
    private final double couponPerPeriod;   // fraction of notional
    private final boolean memoryCoupons;

    /**
     * @param notional         redemption amount, e.g. 1_000_000
     * @param observationYears strictly increasing observation times in
     *                         years; the last is maturity
     * @param autocallBarrier  autocall trigger as a fraction of initial
     *                         spot, e.g. 1.00
     * @param couponBarrier    coupon trigger as a fraction of initial
     *                         spot, ≤ autocallBarrier, e.g. 0.80
     * @param knockInBarrier   protection barrier as a fraction of initial
     *                         spot, e.g. 0.60
     * @param couponPerPeriod  coupon per observation period as a fraction
     *                         of notional, e.g. 0.02
     * @param memoryCoupons    missed coupons are caught up at the next
     *                         paying observation (Phoenix memory)
     */
    public Autocallable(double notional, double[] observationYears, double autocallBarrier,
                        double couponBarrier, double knockInBarrier, double couponPerPeriod,
                        boolean memoryCoupons) {
        // !(x > 0) rejects NaN as well as non-positives: a NaN term must
        // fail HERE, not surface as a NaN price later. The knock-in must
        // sit at or below the autocall (protection above the early-redemption
        // trigger is unreachable); its relation to the COUPON barrier is
        // deliberately free — structures place it on either side.
        if (!(notional > 0) || notional == Double.POSITIVE_INFINITY
                || observationYears.length == 0
                || !(autocallBarrier > 0) || autocallBarrier == Double.POSITIVE_INFINITY
                || !(couponBarrier > 0) || !(knockInBarrier > 0)
                || couponBarrier > autocallBarrier || knockInBarrier > autocallBarrier
                || !(couponPerPeriod >= 0) || couponPerPeriod == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("invalid autocallable terms");
        }
        double prev = 0;
        for (double t : observationYears) {
            if (!(t > prev)) {
                throw new IllegalArgumentException(
                        "observation times must be strictly increasing and positive");
            }
            prev = t;
        }
        this.notional = notional;
        this.observationYears = observationYears.clone();
        this.autocallBarrier = autocallBarrier;
        this.couponBarrier = couponBarrier;
        this.knockInBarrier = knockInBarrier;
        this.couponPerPeriod = couponPerPeriod;
        this.memoryCoupons = memoryCoupons;
    }

    /**
     * Monte Carlo present value under GBM.
     *
     * @param spot     current underlier level (S₀ for a new issue)
     * @param initial  the strike-setting initial level S₀ (equal to
     *                 {@code spot} at issue; differs for a seasoned note)
     * @param vol      Black-Scholes volatility, per √year — pick it from
     *                 the downside smile region, not ATM (see class doc)
     * @param rate     continuously-compounded discount rate
     * @param divYield continuous dividend yield
     * @param paths    Monte Carlo paths (antithetic: 2 per draw), e.g. 100_000
     * @param seed     RNG seed — fixed seed = reproducible price
     */
    public double price(double spot, double initial, double vol, double rate,
                        double divYield, int paths, long seed) {
        if (!(spot > 0) || !(initial > 0) || paths < 1
                || spot == Double.POSITIVE_INFINITY || initial == Double.POSITIVE_INFINITY
                || !(vol >= 0) || vol == Double.POSITIVE_INFINITY
                || !Double.isFinite(rate) || !Double.isFinite(divYield)) {
            throw new IllegalArgumentException("invalid market inputs");
        }
        int n = observationYears.length;
        double[] drift = new double[n];
        double[] volStep = new double[n];
        double[] discount = new double[n];
        double prevT = 0;
        for (int i = 0; i < n; i++) {
            double dt = observationYears[i] - prevT;
            drift[i] = (rate - divYield - 0.5 * vol * vol) * dt;
            volStep[i] = vol * Math.sqrt(dt);
            discount[i] = Math.exp(-rate * observationYears[i]);
            prevT = observationYears[i];
        }

        Random rnd = new Random(seed);
        double[] gaussians = new double[n];
        double sum = 0;
        for (int p = 0; p < paths; p++) {
            for (int i = 0; i < n; i++) {
                gaussians[i] = rnd.nextGaussian();
            }
            sum += pathValue(spot, initial, drift, volStep, discount, gaussians, +1);
            sum += pathValue(spot, initial, drift, volStep, discount, gaussians, -1);
        }
        return sum / (2.0 * paths);
    }

    /** One path's discounted payoff; {@code sign} flips the draws (antithetic). */
    private double pathValue(double spot, double initial, double[] drift,
                             double[] volStep, double[] discount, double[] gaussians,
                             int sign) {
        double s = spot;
        double value = 0;
        double missedCoupons = 0;
        double coupon = couponPerPeriod * notional;
        int n = observationYears.length;
        for (int i = 0; i < n; i++) {
            s *= Math.exp(drift[i] + sign * volStep[i] * gaussians[i]);
            double level = s / initial;
            double df = discount[i];

            if (level >= autocallBarrier) {
                // Early redemption: notional + this coupon + any memory.
                value += df * (notional + coupon + (memoryCoupons ? missedCoupons : 0));
                return value;
            }
            if (level >= couponBarrier) {
                value += df * (coupon + (memoryCoupons ? missedCoupons : 0));
                missedCoupons = 0;
            } else {
                missedCoupons += coupon;
            }
            if (i == n - 1) {
                // Maturity without autocall: protected unless knocked in.
                value += df * (level >= knockInBarrier ? notional : notional * level);
            }
        }
        return value;
    }

    public double notional() {
        return notional;
    }

    public int observations() {
        return observationYears.length;
    }
}
