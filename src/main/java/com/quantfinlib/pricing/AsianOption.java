package com.quantfinlib.pricing;

import com.quantfinlib.util.MathUtils;

/**
 * ASIAN (average-price) options — the corporate hedger's option: paying off
 * on the AVERAGE of n fixings instead of one closing print kills both the
 * expiry-day manipulation incentive and most of the vol (an average is
 * smoother than its endpoints), which is why commodity and FX hedging
 * programs default to them. Two averages, two methods:
 *
 * <ul>
 *   <li><b>Geometric average — exact.</b> A product of lognormals is
 *       lognormal, so the geometric Asian has a Black-Scholes-style closed
 *       form (Kemna-Vorst 1990, discrete-fixing version). For n fixings
 *       equally spaced at {@code t_i = i T / n} (last fixing AT expiry):
 *       <pre>
 *         E[ln G]   = ln S + (r - q - vol^2/2) * T (n+1)/(2n)
 *         Var[ln G] = vol^2 * T * (n+1)(2n+1)/(6 n^2)
 *       </pre>
 *       and the price is the Black-76 form on the lognormal G: discount
 *       {@code E[G] N(d1) - K N(d2)}. As n grows, Var goes to
 *       {@code vol^2 T / 3} — continuous averaging cuts the variance to a
 *       THIRD; at n = 1 both moments collapse to the terminal price and
 *       the formula IS vanilla Black-Scholes (tested exact).</li>
 *   <li><b>Arithmetic average — Turnbull-Wakeman (1991) moment matching.</b>
 *       A sum of lognormals is not lognormal and has no closed form; TW
 *       computes the arithmetic average's first two moments EXACTLY under
 *       GBM,
 *       <pre>
 *         M1 = (S/n) sum_i e^{g t_i}                          g = r - q
 *         M2 = (S/n)^2 sum_i sum_j e^{g(t_i + t_j) + vol^2 min(t_i, t_j)}
 *       </pre>
 *       then prices Black-76 style on the lognormal with those moments
 *       ({@code Var[ln A] = ln(M2/M1^2)}). The approximation error is the
 *       distance of the true density from lognormal — small at practical
 *       vols, growing with vol^2 T; the moments themselves are exact. The
 *       double sum is O(n^2): fine for real fixing schedules (n &le; a few
 *       hundred), not a Monte Carlo replacement for n in the tens of
 *       thousands.</li>
 * </ul>
 *
 * <p>AM-GM guarantees {@code A >= G} pathwise, so an arithmetic CALL is
 * always worth at least the geometric call (tested) — the geometric price
 * is the standard control variate for Monte Carlo on the arithmetic. Rates
 * and vol are annualized and continuously compounded; {@code carry} is the
 * dividend/foreign yield exactly as {@link BlackScholes}. Fixings strictly
 * after inception ({@code t_1 = T/n > 0}): a seasoned Asian with fixings
 * already struck is a payoff on the REMAINING average plus a known cash
 * amount — decompose it before calling. Research lane.</p>
 */
public final class AsianOption {

    private AsianOption() {
    }

    /**
     * Exact discrete geometric-average Asian price (Kemna-Vorst).
     *
     * @param averagingPoints number of equally spaced fixings n &ge; 1 at
     *                        {@code t_i = i T / n}; n = 1 is vanilla BS
     */
    public static double geometricPrice(BlackScholes.OptionType type, double spot, double strike,
                                        double rate, double carry, double vol, double timeYears,
                                        int averagingPoints) {
        validate(spot, strike, rate, carry, vol, timeYears, averagingPoints);
        double n = averagingPoints;
        double meanLog = Math.log(spot)
                + (rate - carry - 0.5 * vol * vol) * timeYears * (n + 1) / (2 * n);
        double varLog = vol * vol * timeYears * (n + 1) * (2 * n + 1) / (6 * n * n);
        double forward = Math.exp(meanLog + 0.5 * varLog);
        return blackOnLognormal(type, forward, strike, varLog, rate, timeYears);
    }

    /**
     * Arithmetic-average Asian price via Turnbull-Wakeman two-moment
     * lognormal matching (see class doc; O(n^2) in the fixing count).
     */
    public static double arithmeticPrice(BlackScholes.OptionType type, double spot, double strike,
                                         double rate, double carry, double vol, double timeYears,
                                         int averagingPoints) {
        validate(spot, strike, rate, carry, vol, timeYears, averagingPoints);
        int n = averagingPoints;
        double h = timeYears / n;
        double g = rate - carry;
        double m1 = 0;
        for (int i = 1; i <= n; i++) {
            m1 += Math.exp(g * i * h);
        }
        m1 *= spot / n;
        double m2 = 0;
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= n; j++) {
                m2 += Math.exp(g * (i + j) * h + vol * vol * Math.min(i, j) * h);
            }
        }
        m2 *= spot * spot / ((double) n * n);
        // Matched log-variance; guard the deterministic edge (vol = 0 makes
        // M2 exactly M1^2 and the log a rounding-noise 0/0).
        double varLog = m2 > m1 * m1 ? Math.log(m2 / (m1 * m1)) : 0;
        return blackOnLognormal(type, m1, strike, varLog, rate, timeYears);
    }

    /** Discounted Black-style price on a lognormal with mean f, log-variance v2. */
    private static double blackOnLognormal(BlackScholes.OptionType type, double f, double k,
                                           double v2, double rate, double timeYears) {
        double df = Math.exp(-rate * timeYears);
        if (v2 <= 0) {
            return df * Math.max(0, type.sign() * (f - k));   // zero-vol intrinsic on the forward
        }
        double sd = Math.sqrt(v2);
        double d1 = (Math.log(f / k) + 0.5 * v2) / sd;
        double d2 = d1 - sd;
        int s = type.sign();
        return df * s * (f * MathUtils.normCdf(s * d1) - k * MathUtils.normCdf(s * d2));
    }

    private static void validate(double spot, double strike, double rate, double carry,
                                 double vol, double timeYears, int averagingPoints) {
        if (!(spot > 0) || spot == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("spot must be positive and finite, got " + spot);
        }
        if (!(strike > 0) || strike == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("strike must be positive and finite, got " + strike);
        }
        if (!Double.isFinite(rate) || !Double.isFinite(carry)) {
            throw new IllegalArgumentException("rate and carry must be finite");
        }
        if (!(vol >= 0) || vol == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("vol must be >= 0 and finite, got " + vol);
        }
        if (!(timeYears > 0) || timeYears == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException(
                    "timeYears must be positive and finite, got " + timeYears);
        }
        if (averagingPoints < 1) {
            throw new IllegalArgumentException(
                    "averagingPoints must be >= 1, got " + averagingPoints);
        }
    }
}
