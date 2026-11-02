package com.quantfinlib.credit;

import com.quantfinlib.rates.YieldCurve;
import com.quantfinlib.stats.SpecialFunctions;

import java.util.Arrays;
import java.util.Objects;

/**
 * Unilateral CVA — the price of the counterparty in every derivative you
 * hold: the expected loss from their default before your trades' cash
 * flows finish arriving. The standard discrete approximation the desks
 * carry:
 *
 * <pre>
 *   CVA = LGD * sum_i EE(t_i) * [ Q(t_{i-1}) - Q(t_i) ] * DF(t_i)
 * </pre>
 *
 * expected exposure at each bucket, times the probability of defaulting in
 * that bucket (read off the {@link CreditCurve}'s survival function), times
 * the discount factor, times loss-given-default. The three ingredients are
 * separate objects: exposure comes from your pricing/
 * simulation stack (this class does not know your portfolio — feed it
 * {@code CounterpartyExposureTracker} peaks, a swap's expected-exposure
 * hump, or Monte Carlo EE averages), credit comes from the CDS-bootstrapped
 * curve, discount from the {@link YieldCurve}.
 *
 * <p>Approximations: exposure is evaluated at the bucket end and
 * assumed constant across the bucket (O(dt) bias; shrink the grid to
 * shrink it, or use {@link #cvaBucketAverage}, which averages the bucket
 * and is O(dt^2)). Default and exposure are independent, so there is no
 * wrong-way risk, which understates CVA when exposure grows exactly when
 * the counterparty weakens (the FX-forward-with-an-EM-sovereign classic).
 * Unilateral: your own default (DVA) is not netted. LGD is a constant you
 * pass, usually {@code 1 - recovery} on the same convention as the curve's
 * bootstrap, but kept separate because the curve's recovery is a quoting
 * convention while CVA's LGD is a modeling choice. Research lane,
 * deterministic.</p>
 *
 * <p>The {@link ExposureProfile} overloads take a simulated profile from
 * {@link MultiCurrencyExposureSimulator} instead: CVA and DVA on its stochastic
 * discounting, and {@link #cvaWrongWay} for wrong-way risk through a
 * Gaussian copula between default and a market factor.</p>
 */
public final class CvaApproximator {

    private CvaApproximator() {
    }

    /**
     * Discrete unilateral CVA over the given exposure profile.
     *
     * @param expectedExposure EE(t_i) per bucket, &ge; 0 (same currency
     *                         units as the answer)
     * @param bucketEndYears   bucket end times t_i in years, strictly
     *                         ascending, all &gt; 0; t_0 = 0 is implicit
     * @param counterparty     the counterparty's bootstrapped credit curve
     * @param discount         risk-free discounting curve
     * @param lgd              loss given default in (0, 1]
     * @return the CVA charge (positive; subtract it from the risk-free PV)
     */
    public static double cva(double[] expectedExposure, double[] bucketEndYears,
                             CreditCurve counterparty, YieldCurve discount, double lgd) {
        int n = expectedExposure.length;
        if (n == 0 || bucketEndYears.length != n) {
            throw new IllegalArgumentException("need aligned, non-empty exposure/time arrays, got "
                    + n + "/" + bucketEndYears.length);
        }
        if (!(lgd > 0) || !(lgd <= 1)) {
            throw new IllegalArgumentException("lgd must be in (0, 1], got " + lgd);
        }
        double prevT = 0;
        for (int i = 0; i < n; i++) {
            if (!(bucketEndYears[i] > prevT) || bucketEndYears[i] == Double.POSITIVE_INFINITY) {
                throw new IllegalArgumentException(
                        "bucket times must be strictly ascending, positive and finite: t["
                                + i + "]=" + bucketEndYears[i]);
            }
            prevT = bucketEndYears[i];
            if (!(expectedExposure[i] >= 0) || expectedExposure[i] == Double.POSITIVE_INFINITY) {
                throw new IllegalArgumentException(
                        "expected exposure must be >= 0 and finite: EE[" + i + "]="
                                + expectedExposure[i]);
            }
        }
        double sum = 0;
        double prevQ = 1;   // Q(0)
        for (int i = 0; i < n; i++) {
            double q = counterparty.survivalProbability(bucketEndYears[i]);
            sum += expectedExposure[i] * (prevQ - q) * discount.discountFactor(bucketEndYears[i]);
            prevQ = q;
        }
        return lgd * sum;
    }

    /**
     * Discrete unilateral CVA with the discounted exposure averaged over each
     * bucket (trapezoid rule), added in 3.1.
     *
     * <p><b>Plain idea.</b> {@link #cva(double[], double[], CreditCurve, YieldCurve, double)}
     * charges each bucket's default probability at the exposure of the bucket
     * end. When exposure rises across the bucket that overstates CVA, when it
     * falls it understates it, and the error shrinks only linearly with the
     * bucket width. Averaging the two ends of the bucket removes the
     * first-order term, which is the usual choice on a coarse grid (Gregory,
     * The xVA Challenge, 3rd ed., 2015, section 14.2: the expected exposure in
     * the CVA sum is the average over the interval).</p>
     *
     * <p><b>Formal statement.</b> CVA is the integral
     * {@code LGD * int_0^T DF(t) EE(t) dPD(t)}. With {@code DEE(t) = DF(t) EE(t)}
     * this method computes</p>
     *
     * <pre>
     *   CVA = LGD * sum_i [ DEE(t_{i-1}) + DEE(t_i) ] / 2 * [ Q(t_{i-1}) - Q(t_i) ]
     * </pre>
     *
     * <p>with {@code DEE(0) = eeAtZero}. For smooth EE and hazard the error
     * against the integral is O(dt^2) instead of O(dt) (tested against a
     * 200 000-step integral, independent recomputation).</p>
     *
     * <p><b>Worked example.</b> EE(t) = 100 t on (0, 1], one bucket, flat
     * hazard 2 %, zero rates, LGD 1: the integral is 100 * int_0^1 t * 0.02
     * e^{-0.02 t} dt = 0.9868; the end-point sum gives 100 * (1 - e^{-0.02})
     * = 1.980 and this method 50 * (1 - e^{-0.02}) = 0.990 (own arithmetic).</p>
     *
     * <p>The end-point method is unchanged and stays the default: its numbers
     * are what existing users reconcile against, and its bias was documented.
     * Choose this method for new work on coarse grids.</p>
     *
     * @param eeAtZero         EE(0), &ge; 0 and finite (0 for a trade at par
     *                         with no collateral imbalance)
     * @param expectedExposure EE(t_i) per bucket end, &ge; 0
     * @param bucketEndYears   bucket end times, strictly ascending, all &gt; 0
     * @param counterparty     the counterparty's bootstrapped credit curve
     * @param discount         risk-free discounting curve
     * @param lgd              loss given default in (0, 1]
     * @return the CVA charge (positive)
     */
    public static double cvaBucketAverage(double eeAtZero, double[] expectedExposure, double[] bucketEndYears,
                                          CreditCurve counterparty, YieldCurve discount, double lgd) {
        if (!(eeAtZero >= 0) || eeAtZero == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("eeAtZero must be >= 0 and finite, got " + eeAtZero);
        }
        checkGrid(expectedExposure, bucketEndYears, lgd);
        Objects.requireNonNull(counterparty, "counterparty");
        Objects.requireNonNull(discount, "discount");
        double sum = 0;
        double prevQ = 1;
        double prevDee = eeAtZero;
        for (int i = 0; i < expectedExposure.length; i++) {
            double t = bucketEndYears[i];
            double q = counterparty.survivalProbability(t);
            double dee = expectedExposure[i] * discount.discountFactor(t);
            sum += 0.5 * (prevDee + dee) * (prevQ - q);
            prevQ = q;
            prevDee = dee;
        }
        return lgd * sum;
    }

    private static void checkGrid(double[] expectedExposure, double[] bucketEndYears, double lgd) {
        int n = expectedExposure.length;
        if (n == 0 || bucketEndYears.length != n) {
            throw new IllegalArgumentException("need aligned, non-empty exposure/time arrays, got "
                    + n + "/" + bucketEndYears.length);
        }
        checkLgd(lgd);
        double prevT = 0;
        for (int i = 0; i < n; i++) {
            if (!(bucketEndYears[i] > prevT) || bucketEndYears[i] == Double.POSITIVE_INFINITY) {
                throw new IllegalArgumentException(
                        "bucket times must be strictly ascending, positive and finite: t["
                                + i + "]=" + bucketEndYears[i]);
            }
            prevT = bucketEndYears[i];
            if (!(expectedExposure[i] >= 0) || expectedExposure[i] == Double.POSITIVE_INFINITY) {
                throw new IllegalArgumentException(
                        "expected exposure must be >= 0 and finite: EE[" + i + "]="
                                + expectedExposure[i]);
            }
        }
    }

    /**
     * CVA from a simulated exposure profile, independent default:
     * {@code LGD * sum_i E[D(0,t_i) E(t_i)] * [Q(t_{i-1}) - Q(t_i)]}. The
     * discounting is the simulation's own stochastic discount factor, so
     * no separate curve is passed.
     *
     * @param profile      simulated netting-set exposure
     * @param counterparty counterparty credit curve
     * @param lgd          loss given default in (0, 1]
     * @return the CVA charge
     */
    public static double cva(ExposureProfile profile, CreditCurve counterparty, double lgd) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(counterparty, "counterparty");
        checkLgd(lgd);
        double sum = 0;
        double prevQ = 1;
        for (int i = 0; i < profile.timesLength(); i++) {
            double q = counterparty.survivalProbability(profile.time(i));
            sum += profile.discountedExpectedExposure(i) * (prevQ - q);
            prevQ = q;
        }
        return lgd * sum;
    }

    /**
     * DVA from a simulated exposure profile: the same sum over the
     * negative exposure and the bank's own credit curve. Unilateral (no
     * first-to-default conditioning) and positive; bilateral CVA is
     * {@code cva - dva}.
     *
     * @param profile simulated netting-set exposure
     * @param own     the bank's own credit curve
     * @param ownLgd  own loss given default in (0, 1]
     * @return the DVA benefit
     */
    public static double dva(ExposureProfile profile, CreditCurve own, double ownLgd) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(own, "own");
        checkLgd(ownLgd);
        double sum = 0;
        double prevQ = 1;
        for (int i = 0; i < profile.timesLength(); i++) {
            double q = own.survivalProbability(profile.time(i));
            sum += profile.discountedExpectedNegativeExposure(i) * (prevQ - q);
            prevQ = q;
        }
        return ownLgd * sum;
    }

    /**
     * CVA with wrong-way risk through a Gaussian copula between default
     * and a market factor, applied bucket by bucket (Li 2000; Gregory,
     * The xVA Challenge, 3rd ed., 2015, ch. 17; the per-bucket conditioning
     * follows Hull and White, "CVA and wrong-way risk", Financial Analysts
     * Journal 68(5), 2012, where default is tied to the market variable
     * that drives the exposure at each date). For bucket i the latent
     * credit variable is {@code Z_i = -rho X_i + sqrt(1 - rho^2) eps},
     * eps independent of the market, and default falls in the bucket when
     * {@code Phi^{-1}(PD_{i-1}) < Z_i <= Phi^{-1}(PD_i)}. X_i is a standard
     * normal driver read path-wise from the simulation:
     *
     * <ul>
     *   <li>uncollateralised set: {@code X_i = W(t_i) / sqrt(t_i)}, the
     *   standardised factor Brownian at the bucket date;</li>
     *   <li>set under a CSA with margin period of risk M:
     *   {@code X_i = (W(t_i) - W(t_i - M)) / sqrt(M)}, the standardised
     *   increment over the MPR ending at t_i, which is what drives the
     *   collateralised exposure {@code max(V(t_i) - C(t_i - M), 0)}.</li>
     * </ul>
     *
     * <p>Conditioning on X_i gives each bucket probability in closed form:</p>
     *
     * <pre>
     *   P(bucket i | X_i) = Phi((Phi^{-1}(PD_i) + rho X_i) / sqrt(1 - rho^2))
     *                     - Phi((Phi^{-1}(PD_{i-1}) + rho X_i) / sqrt(1 - rho^2))
     *   CVA = LGD * mean over paths of sum_i D(0,t_i) E(t_i) P(bucket i | X_i)
     * </pre>
     *
     * <p>Since X_i is standard normal, E[P(bucket i | X_i)] = PD_i - PD_{i-1}:
     * the marginal default curve is unchanged, and rho = 0 reproduces
     * {@link #cva(ExposureProfile, CreditCurve, double)}. Positive rho makes
     * default more likely when the driver is high: wrong-way for a payer
     * swap with {@link ExposureProfile.Factor#RATES} and for buying foreign
     * currency with {@link ExposureProfile.Factor#FX}. Model limits: the
     * bucket probabilities are a marginal construction (each bucket has its
     * own driver, so there is no single joint default time across buckets),
     * and under a CSA with a large threshold the exposure also depends on
     * the factor level, which the increment driver ignores. Before 2.1 the
     * driver was the single terminal factor value for every bucket; that
     * understated wrong-way risk on collateralised sets, where it could
     * even fall below the independent CVA.</p>
     *
     * @param profile      simulated exposure
     * @param counterparty counterparty credit curve
     * @param lgd          loss given default in (0, 1]
     * @param rho          copula correlation in (-1, 1)
     * @param driver       market factor that drives default
     * @return the CVA charge
     */
    public static double cvaWrongWay(ExposureProfile profile, CreditCurve counterparty, double lgd,
                                     double rho, ExposureProfile.Factor driver) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(counterparty, "counterparty");
        Objects.requireNonNull(driver, "driver");
        checkLgd(lgd);
        if (!(rho > -1) || !(rho < 1)) {
            throw new IllegalArgumentException("rho must be in (-1, 1), got " + rho);
        }
        int m = profile.timesLength();
        double[] thresholds = new double[m];
        for (int i = 0; i < m; i++) {
            double pd = counterparty.defaultProbability(profile.time(i));
            if (pd <= 0) {
                thresholds[i] = Double.NEGATIVE_INFINITY;
            } else if (pd >= 1) {
                thresholds[i] = Double.POSITIVE_INFINITY;
            } else {
                thresholds[i] = SpecialFunctions.normInv(pd);
            }
        }
        double scale = Math.sqrt(1 - rho * rho);
        double total = 0;
        int n = profile.paths();
        for (int p = 0; p < n; p++) {
            double sum = 0;
            for (int i = 0; i < m; i++) {
                double shift = rho * profile.driver(p, i, driver);
                double lo = i == 0 ? 0 : conditionalCdf(thresholds[i - 1], shift, scale);
                double hi = conditionalCdf(thresholds[i], shift, scale);
                sum += profile.discountedPositive(p, i) * (hi - lo);
            }
            total += sum;
        }
        return lgd * total / n;
    }

    private static double conditionalCdf(double threshold, double shift, double scale) {
        if (threshold == Double.NEGATIVE_INFINITY) {
            return 0;
        }
        if (threshold == Double.POSITIVE_INFINITY) {
            return 1;
        }
        return SpecialFunctions.normCdf((threshold + shift) / scale);
    }

    /**
     * Bilateral CVA and DVA with first-to-default conditioning.
     *
     * @param cva counterparty-default loss, charged only if the bank has not defaulted first
     * @param dva own-default benefit, counted only if the counterparty has not defaulted first
     */
    public record Bilateral(double cva, double dva) {
        /**
         * Bilateral adjustment, a charge when positive.
         *
         * @return {@code cva - dva}
         */
        public double total() {
            return cva - dva;
        }
    }

    /**
     * Bilateral CVA/DVA from a simulated profile with first-to-default
     * (Brigo and Capponi, "Bilateral counterparty risk with application to
     * CDSs", 2008; Gregory, The xVA Challenge, 3rd ed., 2015, ch. 14).
     *
     * <p>Plain idea: a counterparty default only costs the bank if the bank
     * is still alive, and an own default only helps if the counterparty is
     * still alive. Formal statement, with the two defaults independent of
     * each other and of the exposure, and each hazard taken flat inside every
     * profile bucket (so the bucket integral is exact for curves whose
     * hazard is piecewise flat on the bucket grid):</p>
     *
     * <pre>
     *   CVA = LGD_c sum_i DEE_i  * Lc_i / (Lc_i + Lo_i) * (S(t_{i-1}) - S(t_i))
     *   DVA = LGD_o sum_i DENE_i * Lo_i / (Lc_i + Lo_i) * (S(t_{i-1}) - S(t_i))
     *   S(t) = Q_c(t) Q_o(t),  L_i = ln(Q(t_{i-1}) / Q(t_i))
     * </pre>
     *
     * <p>Worked example: flat hazards 2 % and 1 %, one bucket (0, 5] with
     * DEE = 10: CVA = 0.6 * 10 * 2/3 * (1 - e^{-0.15}) = 0.557, against the
     * unilateral 0.6 * 10 * (1 - e^{-0.1}) = 0.571 (own arithmetic). As the
     * own hazard goes to zero the CVA leg tends to the unilateral
     * {@link #cva(ExposureProfile, CreditCurve, double)} (tested). Wrong-way
     * risk is not combined with this method.</p>
     *
     * @param profile      simulated exposure
     * @param counterparty counterparty credit curve
     * @param lgd          counterparty LGD in (0, 1]
     * @param own          the bank's own credit curve
     * @param ownLgd       own LGD in (0, 1]
     * @return both legs
     */
    public static Bilateral bilateral(ExposureProfile profile, CreditCurve counterparty, double lgd,
                                      CreditCurve own, double ownLgd) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(counterparty, "counterparty");
        Objects.requireNonNull(own, "own");
        checkLgd(lgd);
        checkLgd(ownLgd);
        double c = 0, d = 0, qc0 = 1, qo0 = 1;
        for (int i = 0; i < profile.timesLength(); i++) {
            double t = profile.time(i);
            double qc = counterparty.survivalProbability(t), qo = own.survivalProbability(t);
            double lc = Math.log(qc0 / qc), lo = Math.log(qo0 / qo);
            double ds = qc0 * qo0 - qc * qo;
            if (lc + lo > 0) {
                c += profile.discountedExpectedExposure(i) * lc / (lc + lo) * ds;
                d += profile.discountedExpectedNegativeExposure(i) * lo / (lc + lo) * ds;
            }
            qc0 = qc;
            qo0 = qo;
        }
        return new Bilateral(lgd * c, ownLgd * d);
    }

    /**
     * Bilateral CVA/DVA with wrong-way risk on both names and one
     * path-consistent first-to-default (added in 2.3).
     *
     * <p><b>Plain idea.</b> Each name has a default intensity that rises
     * or falls with the same market driver that moves the exposure. On a
     * path, both names then have one default time each, the earlier one
     * ends the contract, and only that default produces a loss (CVA) or a
     * benefit (DVA). Positive {@code bCpty} on a driver that raises the
     * exposure is wrong-way risk and raises the CVA (tested).</p>
     *
     * <p><b>Formal statement.</b> A driven-hazard model in the style of
     * Hull and White, "CVA and wrong-way risk", Financial Analysts Journal
     * 68(5), 2012 (their hazard-rate approach, with the hazard taken flat
     * in each profile bucket). In bucket i, on path p, the integrated
     * hazards are</p>
     *
     * <pre>
     *   Lc_i(p) = exp(alpha_i + bCpty * X_i(p)),   Lo_i(p) = exp(beta_i + bOwn * X_i(p))
     * </pre>
     *
     * <p>with X_i the profile's standardised driver (see
     * {@link #cvaWrongWay(ExposureProfile, CreditCurve, double, double, ExposureProfile.Factor)}).
     * alpha_i and beta_i are solved bucket by bucket so that the path average
     * of {@code exp(-sum_{j<=i} L_j(p))} equals each name's curve survival
     * Q(t_i): the marginal default curves are matched exactly on the
     * simulated paths. Given the market path the two default times are
     * independent (conditional independence is the stated copula; the
     * names are dependent only through the common driver), so with
     * Sc, So the path survivals to t_{i-1}:</p>
     *
     * <pre>
     *   P(cpty first in bucket i | path) = Sc So Lc / (Lc + Lo) (1 - e^{-(Lc + Lo)})
     *   CVA = LGD_c mean_p sum_i D E_i(p) P(cpty first in bucket i | p),  DVA likewise with D NE and Lo
     * </pre>
     *
     * <p>With {@code bCpty = bOwn = 0} the hazards are the deterministic
     * bucket hazards and the result equals
     * {@link #bilateral(ExposureProfile, CreditCurve, double, CreditCurve, double)}
     * to rounding (tested). A brute-force simulation of the two default
     * times agrees within Monte Carlo error (tested, independent
     * computation). Assumptions: hazards flat inside a bucket, default
     * only matters at bucket granularity, no recovery or LGD randomness,
     * and the driver is the profile's per-bucket X_i, so the dependence is
     * on the factor level (uncollateralised) or its MPR increment (CSA).
     * Worked example: a 10y payer swap with bCpty = 0.5 on the rates driver
     * gives a CVA above the independent bilateral value (see
     * BilateralWrongWayCvaTest for the numbers, own test).</p>
     *
     * @param profile      simulated exposure
     * @param counterparty counterparty credit curve, survival &gt; 0 on the grid
     * @param lgd          counterparty LGD in (0, 1]
     * @param own          own credit curve, survival &gt; 0 on the grid
     * @param ownLgd       own LGD in (0, 1]
     * @param bCpty        counterparty hazard loading on the driver (finite)
     * @param bOwn         own hazard loading on the driver (finite)
     * @param driver       market factor that drives both hazards
     * @return both legs
     */
    public static Bilateral bilateralWrongWay(ExposureProfile profile, CreditCurve counterparty, double lgd,
                                              CreditCurve own, double ownLgd, double bCpty, double bOwn,
                                              ExposureProfile.Factor driver) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(counterparty, "counterparty");
        Objects.requireNonNull(own, "own");
        Objects.requireNonNull(driver, "driver");
        checkLgd(lgd);
        checkLgd(ownLgd);
        if (!Double.isFinite(bCpty) || !Double.isFinite(bOwn)) {
            throw new IllegalArgumentException("hazard loadings must be finite");
        }
        int n = profile.paths(), m = profile.timesLength();
        double[][] lc = drivenHazards(profile, counterparty, bCpty, driver);
        double[][] lo = drivenHazards(profile, own, bOwn, driver);
        double c = 0, d = 0;
        for (int p = 0; p < n; p++) {
            double sc = 1, so = 1;
            for (int i = 0; i < m; i++) {
                double a = lc[p][i], b = lo[p][i], s = a + b;
                if (s > 0) {
                    double pBoth = sc * so * -Math.expm1(-s);
                    c += profile.discountedPositive(p, i) * a / s * pBoth;
                    d += profile.discountedNegative(p, i) * b / s * pBoth;
                }
                sc *= Math.exp(-a);
                so *= Math.exp(-b);
            }
        }
        return new Bilateral(lgd * c / n, ownLgd * d / n);
    }

    /** Integrated bucket hazards L_i(p) = exp(alpha_i + b X_i(p)) matched to the curve survival. */
    static double[][] drivenHazards(ExposureProfile profile, CreditCurve curve, double b,
                                    ExposureProfile.Factor driver) {
        int n = profile.paths(), m = profile.timesLength();
        double[][] l = new double[n][m];
        double[] surv = new double[n];
        Arrays.fill(surv, 1);
        double q0 = 1;
        double[] x = new double[n];
        for (int i = 0; i < m; i++) {
            double q = curve.survivalProbability(profile.time(i));
            if (!(q > 0)) {
                throw new IllegalArgumentException("survival must be positive on the profile grid");
            }
            double base = Math.log(q0 / q);
            if (base > 0) {
                for (int p = 0; p < n; p++) {
                    x[p] = b * profile.driver(p, i, driver);
                }
                double alpha = Math.log(base);
                if (b != 0) {
                    alpha = solveAlpha(surv, x, q, alpha);
                }
                for (int p = 0; p < n; p++) {
                    l[p][i] = b == 0 ? base : Math.exp(alpha + x[p]);
                    surv[p] *= Math.exp(-l[p][i]);
                }
            }
            q0 = q;
        }
        return l;
    }

    /** Solves mean_p surv_p exp(-exp(alpha + x_p)) = target by safeguarded Newton. */
    private static double solveAlpha(double[] surv, double[] x, double target, double start) {
        int n = surv.length;
        double lo = Double.NEGATIVE_INFINITY, hi = Double.POSITIVE_INFINITY, a = start;
        for (int it = 0; it < 200; it++) {
            double f = 0, fp = 0;
            for (int p = 0; p < n; p++) {
                double h = Math.exp(a + x[p]);
                double e = surv[p] * Math.exp(-h);
                f += e;
                fp -= e * h;
            }
            f = f / n - target;
            fp /= n;
            if (Math.abs(f) <= 1e-15 * target) {
                return a;
            }
            if (f > 0) {
                lo = a;     // survival too high: raise alpha
            } else {
                hi = a;
            }
            double next = fp < 0 ? a - f / fp : Double.NaN;
            if (!(next > lo && next < hi)) {
                if (lo == Double.NEGATIVE_INFINITY) {
                    next = hi - 1;
                } else if (hi == Double.POSITIVE_INFINITY) {
                    next = lo + 1;
                } else {
                    next = 0.5 * (lo + hi);
                }
            }
            if (Math.abs(next - a) <= 1e-15 * Math.max(1, Math.abs(a))) {
                return a;
            }
            a = next;
        }
        throw new IllegalStateException("hazard calibration did not converge; target survival "
                + target + " may be unreachable on these paths");
    }

    private static void checkLgd(double lgd) {
        if (!(lgd > 0) || !(lgd <= 1)) {
            throw new IllegalArgumentException("lgd must be in (0, 1], got " + lgd);
        }
    }
}
