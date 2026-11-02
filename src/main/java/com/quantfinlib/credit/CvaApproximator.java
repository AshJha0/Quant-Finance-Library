package com.quantfinlib.credit;

import com.quantfinlib.rates.YieldCurve;
import com.quantfinlib.util.MathUtils;

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
 * shrink it). Default and exposure are independent, so there is no
 * wrong-way risk, which understates CVA when exposure grows exactly when
 * the counterparty weakens (the FX-forward-with-an-EM-sovereign classic).
 * Unilateral: your own default (DVA) is not netted. LGD is a constant you
 * pass, usually {@code 1 - recovery} on the same convention as the curve's
 * bootstrap, but kept separate because the curve's recovery is a quoting
 * convention while CVA's LGD is a modeling choice. Research lane,
 * deterministic.</p>
 *
 * <p>The {@link ExposureProfile} overloads take a simulated profile from
 * {@link ExposureSimulator} instead: CVA and DVA on its stochastic
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
                thresholds[i] = MathUtils.normInv(pd);
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
        return MathUtils.normCdf((threshold + shift) / scale);
    }

    private static void checkLgd(double lgd) {
        if (!(lgd > 0) || !(lgd <= 1)) {
            throw new IllegalArgumentException("lgd must be in (0, 1], got " + lgd);
        }
    }
}
