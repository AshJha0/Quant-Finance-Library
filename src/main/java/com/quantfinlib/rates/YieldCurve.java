package com.quantfinlib.rates;

import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeMap;

/**
 * Zero-coupon yield curve — the single most load-bearing object in fixed
 * income: every bond price, swap value, forward rate and DV01 is a
 * function of it. The curve stores continuously-compounded ZERO rates at
 * pillar tenors; everything else is derived: the discount factor
 * {@code DF(t) = e^{-z(t)·t}} answers "what is 1 unit at time t worth
 * today", and the implied forward between t1 and t2 falls out of the
 * ratio of discount factors — the market's own break-even rate for that
 * future period, no forecast involved.
 *
 * <p>You rarely observe zero rates directly; the market quotes PAR
 * instruments (deposits, swaps). Bootstrapping walks the quotes from
 * shortest to longest, at each pillar solving for the one discount factor
 * that reprices the quote given the factors already solved — for annual
 * par swaps, {@code DF_n = (1 - parRate_n · A_{n-1}) / (1 + parRate_n)}
 * with {@code A} the annuity so far. The result reprices every input
 * exactly (tested), which is the definition of a usable curve.</p>
 *
 * <p>Model choices, stated: linear interpolation ON ZERO RATES (simple,
 * fast, and the standard first choice; its known wart is small forward-rate
 * kinks at pillars — smoothing splines fix that at the cost of locality),
 * flat extrapolation beyond the pillars, and one curve for both
 * discounting and projection (pre-2008 style; a multi-curve OIS/projection
 * split is a composition of two of these). Research/warm lane.</p>
 */
public final class YieldCurve {

    private final TreeMap<Double, Double> zeros = new TreeMap<>();   // tenorYears -> cc zero rate

    private YieldCurve() {
    }

    /** Curve from parallel arrays of tenors (years) and continuous zero rates. */
    public static YieldCurve ofZeroRates(double[] tenorYears, double[] zeroRatesCc) {
        if (tenorYears.length != zeroRatesCc.length || tenorYears.length == 0) {
            throw new IllegalArgumentException("need equal-length, non-empty tenor/rate arrays");
        }
        YieldCurve c = new YieldCurve();
        for (int i = 0; i < tenorYears.length; i++) {
            if (tenorYears[i] <= 0) {
                throw new IllegalArgumentException("tenor must be positive: " + tenorYears[i]);
            }
            c.zeros.put(tenorYears[i], zeroRatesCc[i]);
        }
        return c;
    }

    /**
     * Classic bootstrap from par swap rates with an annual fixed leg at
     * integer-year pillars (missing years are filled by linear interpolation
     * of the par rates): {@code DF_n = (1 - parRate_n * A_{n-1}) / (1 + parRate_n)}.
     */
    public static YieldCurve bootstrapAnnualParSwaps(int[] tenorYears, double[] parRates) {
        if (tenorYears.length != parRates.length || tenorYears.length == 0) {
            throw new IllegalArgumentException("need equal-length, non-empty tenor/rate arrays");
        }
        int maxYear = tenorYears[tenorYears.length - 1];
        // Interpolate par rates for every year 1..maxYear.
        double[] par = new double[maxYear + 1];
        int k = 0;
        for (int y = 1; y <= maxYear; y++) {
            while (k < tenorYears.length - 1 && tenorYears[k + 1] < y) {
                k++;
            }
            if (y <= tenorYears[0]) {
                par[y] = parRates[0];
            } else if (y >= tenorYears[tenorYears.length - 1]) {
                par[y] = parRates[parRates.length - 1];
            } else {
                int lo = k, hi = k + 1;
                double w = (double) (y - tenorYears[lo]) / (tenorYears[hi] - tenorYears[lo]);
                par[y] = parRates[lo] + w * (parRates[hi] - parRates[lo]);
            }
        }
        YieldCurve c = new YieldCurve();
        double annuity = 0;
        for (int y = 1; y <= maxYear; y++) {
            double df = (1 - par[y] * annuity) / (1 + par[y]);
            if (df <= 0) {
                throw new IllegalArgumentException("bootstrap produced non-positive DF at year " + y);
            }
            annuity += df;
            c.zeros.put((double) y, -Math.log(df) / y);
        }
        return c;
    }

    /** Continuously-compounded zero rate (linear interpolation, flat extrapolation). */
    public double zeroRate(double tenorYears) {
        Map.Entry<Double, Double> lo = zeros.floorEntry(tenorYears);
        Map.Entry<Double, Double> hi = zeros.ceilingEntry(tenorYears);
        if (lo == null) {
            return hi.getValue();
        }
        if (hi == null) {
            return lo.getValue();
        }
        if (lo.getKey().equals(hi.getKey())) {
            return lo.getValue();
        }
        double w = (tenorYears - lo.getKey()) / (hi.getKey() - lo.getKey());
        return lo.getValue() + w * (hi.getValue() - lo.getValue());
    }

    public double discountFactor(double tenorYears) {
        if (tenorYears <= 0) {
            return 1;
        }
        return Math.exp(-zeroRate(tenorYears) * tenorYears);
    }

    /** Implied continuously-compounded forward rate between two tenors. */
    public double forwardRate(double fromYears, double toYears) {
        if (toYears <= fromYears) {
            throw new IllegalArgumentException("toYears must exceed fromYears");
        }
        double z1 = zeroRate(fromYears) * fromYears;
        double z2 = zeroRate(toYears) * toYears;
        return (z2 - z1) / (toYears - fromYears);
    }

    public NavigableSet<Double> tenors() {
        return zeros.navigableKeySet();
    }
}
