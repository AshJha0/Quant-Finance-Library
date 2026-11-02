package com.quantfinlib.risk;

/**
 * Capital for non-modellable risk factors (NMRF) under the FRTB internal
 * models approach, styled after BCBS d457 chapter MAR33. Not certified.
 *
 * <p><b>Plain idea.</b> Each NMRF gets its own stress-scenario loss.
 * Idiosyncratic credit-spread and idiosyncratic equity factors are
 * each aggregated with zero correlation within its own group, under
 * separate square roots;
 * all remaining NMRFs are aggregated with a single correlation of 0.6,
 * which sits between a plain sum and a root-sum-square.</p>
 *
 * <p><b>Formal statement (per MAR33).</b></p>
 * <pre>  SES = √(Σ ISES_credit,i²) + √(Σ ISES_equity,j²)
 *      + √((ρ Σ SES_k)² + (1 − ρ²) Σ SES_k²),   ρ = 0.6</pre>
 * (MAR33.17, d457). The separate roots for the credit (i = 1..L) and
 * equity (j = 1..K) groups are verified against the printed formula in
 * MAR33.17 (d457).
 *
 * <p><b>Worked example</b> (own test): credit {3, 4}, equity {5},
 * others {10, 10}: √25 + √25 + √(144 + 0.64·200) = 5 + 5 + 16.4924 =
 * 26.4924.</p>
 */
public final class FrtbNmrf {

    private FrtbNmrf() {
    }

    /** Correlation for non-idiosyncratic NMRFs (MAR33.17(4), verified). */
    public static final double RHO = 0.6;

    /** Aggregated stress-scenario capital. All inputs are losses ≥ 0. */
    public static double ses(double[] idioCredit, double[] idioEquity, double[] others) {
        double credit = 0;
        for (double v : idioCredit) {
            check(v);
            credit += v * v;
        }
        double equity = 0;
        for (double v : idioEquity) {
            check(v);
            equity += v * v;
        }
        return Math.sqrt(credit) + Math.sqrt(equity) + correlated(others, RHO);
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

    private static void check(double v) {
        if (!(v >= 0) || v == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("stress losses must be >= 0 and finite");
        }
    }
}
