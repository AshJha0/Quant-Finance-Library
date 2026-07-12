package com.quantfinlib.credit;

import com.quantfinlib.rates.YieldCurve;

/**
 * UNILATERAL CVA — the price of the counterparty in every derivative you
 * hold: the expected loss from their default before your trades' cash
 * flows finish arriving. The standard discrete approximation the desks
 * carry:
 *
 * <pre>
 *   CVA = LGD * sum_i EE(t_i) * [ Q(t_{i-1}) - Q(t_i) ] * DF(t_i)
 * </pre>
 *
 * expected exposure at each bucket, times the probability of defaulting IN
 * that bucket (read off the {@link CreditCurve}'s survival function), times
 * the discount factor, times loss-given-default. The three ingredients are
 * deliberately separate objects: EXPOSURE comes from your pricing/
 * simulation stack (this class does not know your portfolio — feed it
 * {@code CounterpartyExposureTracker} peaks, a swap's expected-exposure
 * hump, or Monte Carlo EE averages), CREDIT comes from the CDS-bootstrapped
 * curve, DISCOUNT from the {@link YieldCurve}.
 *
 * <p>Approximations, stated: exposure is evaluated at the bucket END and
 * assumed constant across the bucket (O(dt) bias, shrink the grid to
 * shrink it); default and exposure are INDEPENDENT — no wrong-way risk,
 * which UNDERSTATES CVA when exposure grows exactly when the counterparty
 * weakens (the FX-forward-with-an-EM-sovereign classic); unilateral — your
 * own default (DVA) is not netted; LGD is a constant you pass, usually
 * {@code 1 - recovery} on the same convention as the curve's bootstrap,
 * but kept separate because the curve's recovery is a quoting convention
 * while CVA's LGD is a modeling choice. Research lane, deterministic.</p>
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
}
