package com.quantfinlib.risk;

/**
 * Capital for non-modellable risk factors (NMRF) under the FRTB internal
 * models approach, styled after BCBS d457 chapter MAR33. Not certified.
 *
 * <p><b>Plain idea.</b> Each NMRF gets its own stress-scenario loss.
 * Idiosyncratic credit-spread and idiosyncratic equity factors are
 * assumed independent of each other (zero correlation within each
 * group); all remaining NMRFs are aggregated with a single correlation
 * of 0.6, which sits between a plain sum and a root-sum-square.</p>
 *
 * <p><b>Formal statement (per MAR33).</b></p>
 * <pre>  SES = √(Σ ISES_credit,i²) + √(Σ ISES_equity,j²)
 *      + √((ρ Σ SES_k)² + (1 − ρ²) Σ SES_k²),   ρ = 0.6</pre>
 *
 * <p><b>Worked example</b> (own test): credit {3, 4}, equity {5},
 * others {10, 10}: 5 + 5 + √(144 + 0.64·200) = 10 + √272 = 26.4924.</p>
 */
public final class FrtbNmrf {

    private FrtbNmrf() {
    }

    /** Correlation for non-idiosyncratic NMRFs. */
    public static final double RHO = 0.6;

    /** Aggregated stress-scenario capital. All inputs are losses ≥ 0. */
    public static double ses(double[] idioCredit, double[] idioEquity, double[] others) {
        return rss(idioCredit) + rss(idioEquity) + correlated(others, RHO);
    }

    static double correlated(double[] x, double rho) {
        double s = 0;
        double sq = 0;
        for (double v : x) {
            check(v);
            s += v;
            sq += v * v;
        }
        return Math.sqrt(rho * rho * s * s + (1 - rho * rho) * sq);
    }

    private static double rss(double[] x) {
        double sq = 0;
        for (double v : x) {
            check(v);
            sq += v * v;
        }
        return Math.sqrt(sq);
    }

    private static void check(double v) {
        if (!(v >= 0) || v == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("stress losses must be >= 0 and finite");
        }
    }
}
