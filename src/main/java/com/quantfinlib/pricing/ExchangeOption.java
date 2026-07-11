package com.quantfinlib.pricing;

import com.quantfinlib.util.MathUtils;

/**
 * TWO-ASSET closed forms: Margrabe's exchange option and Kirk's spread
 * approximation — the workhorse formulas for relative-value option books
 * (crack spreads, calendar spreads, stock-vs-index switches).
 *
 * <p><b>Margrabe (1978)</b> — the right to exchange asset 2 for asset 1
 * (payoff {@code max(0, S1 − S2)}) is a Black-Scholes call in disguise:
 * price asset 1 IN UNITS OF asset 2 and the strike becomes 1, the rate
 * drops out entirely (a ratio has no financing cost) and the vol is the
 * vol of the RATIO, {@code σ² = σ1² + σ2² − 2ρσ1σ2}. The interview
 * observation worth knowing: with ρ = 1 and σ1 = σ2 the ratio has zero
 * vol and the option is pure forward intrinsic — two perfectly correlated
 * equal-vol assets can never finish crossed.</p>
 *
 * <p><b>Kirk (1995)</b> — a spread call {@code max(0, F1 − F2 − K)} has no
 * exact lognormal closed form (a difference of lognormals is not
 * lognormal); Kirk approximates {@code F2 + K} as one lognormal asset with
 * vol scaled by its F2 share, {@code σ_K = sqrt(σ1² − 2ρσ1σ2·f + σ2²·f²)}
 * with {@code f = F2/(F2+K)}. Exact in both limits — K = 0 collapses to
 * Margrabe, F2 = 0 collapses to Black-76 (both pinned) — and accurate to
 * a few bps of premium for moderate K; known to degrade for very large K
 * with high σ2 (stated: it is an approximation, not a theorem).</p>
 *
 * <p>Research lane, deterministic, no smile (single flat vol per leg).</p>
 */
public final class ExchangeOption {

    private ExchangeOption() {
    }

    /**
     * Margrabe: receive asset 1, deliver asset 2 at expiry.
     *
     * @param s1  spot of the asset received, &gt; 0
     * @param s2  spot of the asset delivered, &gt; 0
     * @param q1  asset 1 continuous yield
     * @param q2  asset 2 continuous yield
     * @param rho correlation of the two log-returns, in [-1, 1]
     */
    public static double margrabe(double s1, double s2, double q1, double q2,
                                  double vol1, double vol2, double rho, double timeYears) {
        requirePositive(s1, "s1");
        requirePositive(s2, "s2");
        requireVols(vol1, vol2, rho);
        if (!Double.isFinite(q1) || !Double.isFinite(q2)) {
            throw new IllegalArgumentException("yields must be finite");
        }
        if (timeYears <= 0) {
            return Math.max(0, s1 - s2);
        }
        double variance = vol1 * vol1 + vol2 * vol2 - 2 * rho * vol1 * vol2;
        double f1 = s1 * Math.exp(-q1 * timeYears);
        double f2 = s2 * Math.exp(-q2 * timeYears);
        if (variance <= 0) {
            // Perfectly correlated equal-vol legs: the ratio cannot move.
            return Math.max(0, f1 - f2);
        }
        double sigma = Math.sqrt(variance);
        double sqrtT = Math.sqrt(timeYears);
        double d1 = (Math.log(s1 / s2) + (q2 - q1 + 0.5 * variance) * timeYears)
                / (sigma * sqrtT);
        double d2 = d1 - sigma * sqrtT;
        return f1 * MathUtils.normCdf(d1) - f2 * MathUtils.normCdf(d2);
    }

    /**
     * Kirk's approximation for a spread CALL on two forwards:
     * {@code max(0, F1 − F2 − K)} paid at expiry, discounted at
     * {@code rate}. {@code strike} may be 0 (Margrabe limit) but not
     * negative — flip the legs instead.
     */
    public static double kirkSpreadCall(double f1, double f2, double strike, double rate,
                                        double vol1, double vol2, double rho, double timeYears) {
        requirePositive(f1, "f1");
        if (!(f2 >= 0) || f2 == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("f2 must be >= 0 and finite, got " + f2);
        }
        if (!(strike >= 0) || strike == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("strike must be >= 0 and finite, got " + strike);
        }
        if (f2 + strike <= 0) {
            throw new IllegalArgumentException("f2 + strike must be > 0");
        }
        if (!Double.isFinite(rate)) {
            throw new IllegalArgumentException("rate must be finite");
        }
        requireVols(vol1, vol2, rho);
        double df = Math.exp(-rate * Math.max(timeYears, 0));
        if (timeYears <= 0) {
            return df * Math.max(0, f1 - f2 - strike);
        }
        double f = f2 / (f2 + strike);
        double variance = vol1 * vol1 - 2 * rho * vol1 * vol2 * f + vol2 * vol2 * f * f;
        if (variance <= 0) {
            return df * Math.max(0, f1 - f2 - strike);
        }
        double sigma = Math.sqrt(variance);
        double sqrtT = Math.sqrt(timeYears);
        double d1 = (Math.log(f1 / (f2 + strike)) + 0.5 * variance * timeYears)
                / (sigma * sqrtT);
        double d2 = d1 - sigma * sqrtT;
        return df * (f1 * MathUtils.normCdf(d1) - (f2 + strike) * MathUtils.normCdf(d2));
    }

    private static void requirePositive(double v, String name) {
        if (!(v > 0) || v == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException(name + " must be positive and finite, got " + v);
        }
    }

    private static void requireVols(double vol1, double vol2, double rho) {
        if (!(vol1 >= 0) || vol1 == Double.POSITIVE_INFINITY
                || !(vol2 >= 0) || vol2 == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("vols must be >= 0 and finite");
        }
        if (!(rho >= -1) || !(rho <= 1)) {
            throw new IllegalArgumentException("rho must be in [-1, 1], got " + rho);
        }
    }
}
