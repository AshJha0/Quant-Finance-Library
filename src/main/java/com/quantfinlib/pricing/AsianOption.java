package com.quantfinlib.pricing;

import com.quantfinlib.util.MathUtils;

/**
 * Asian (average-price) options — the corporate hedger's option: paying off
 * on the average of n fixings instead of one closing print kills both the
 * expiry-day manipulation incentive and most of the vol (an average is
 * smoother than its endpoints), which is why commodity and FX hedging
 * programs default to them. Two averages, two methods:
 *
 * <ul>
 *   <li><b>Geometric average: exact.</b> A product of lognormals is
 *       lognormal, so the geometric Asian has a Black-Scholes-style closed
 *       form (Kemna-Vorst 1990, discrete-fixing version). For n fixings
 *       equally spaced at {@code t_i = i T / n} (last fixing at expiry):
 *       <pre>
 *         E[ln G]   = ln S + (r - q - vol^2/2) * T (n+1)/(2n)
 *         Var[ln G] = vol^2 * T * (n+1)(2n+1)/(6 n^2)
 *       </pre>
 *       and the price is the Black-76 form on the lognormal G: discount
 *       {@code E[G] N(d1) - K N(d2)}. As n grows, Var goes to
 *       {@code vol^2 T / 3}: continuous averaging cuts the variance to a
 *       third. At n = 1 both moments collapse to the terminal price and
 *       the formula is vanilla Black-Scholes (tested exact).</li>
 *   <li><b>Arithmetic average: Turnbull-Wakeman (1991) moment matching.</b>
 *       A sum of lognormals is not lognormal and has no closed form; TW
 *       computes the arithmetic average's first two moments exactly under
 *       GBM,
 *       <pre>
 *         M1 = (S/n) sum_i e^{g t_i}                          g = r - q
 *         M2 = (S/n)^2 sum_i sum_j e^{g(t_i + t_j) + vol^2 min(t_i, t_j)}
 *       </pre>
 *       then prices Black-76 style on the lognormal with those moments
 *       ({@code Var[ln A] = ln(M2/M1^2)}). The approximation error is the
 *       distance of the true density from lognormal — small at practical
 *       vols, growing with vol^2 T; the moments themselves are exact. The
 *       double sum is evaluated in O(n) with suffix sums.</li>
 * </ul>
 *
 * <p>AM-GM guarantees {@code A >= G} pathwise, so an arithmetic call is
 * always worth at least the geometric call (tested), and the geometric
 * price is the standard control variate for Monte Carlo on the arithmetic.
 * Rates and vol are annualized and continuously compounded; {@code carry}
 * is the dividend/foreign yield exactly as {@link BlackScholes}. Fixings
 * strictly after inception ({@code t_1 = T/n > 0}) in the two methods above.</p>
 *
 * <p><b>Seasoned and general schedules.</b> {@link #turnbullWakemanSeasoned}
 * and {@link #curranPrice} take arbitrary future fixing times plus the sum
 * and count of fixings already struck. With n fixings in total, m still to
 * come and observed sum {@code A_obs}, the payoff {@code (A - K)^+} equals
 * {@code (m/n) (A_rem - K*)^+} with {@code K* = (n K - A_obs)/m} (Haug 2007,
 * The Complete Guide to Option Pricing Formulas, 2nd ed., section 4.20.1).
 * When {@code K* <= 0} the call is certain to finish in the money and is
 * priced exactly as the discounted forward of {@code A - K}; the put is 0.
 * {@link #curranPrice} conditions on the geometric average (Curran 1994,
 * "Valuing Asian and portfolio options by conditioning on the geometric mean
 * price", Management Science 40(12)); {@link #levyPrice} is the continuous
 * average lognormal approximation of Levy (1992, "Pricing European average
 * rate currency options", J. Int. Money and Finance 11).</p>
 *
 * <p>Research lane.</p>
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
     * lognormal matching (see class doc; O(n) in the fixing count).
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
        // M2 n^2/S^2 = sum_i e^{(2g+v^2) t_i} + 2 sum_i e^{(g+v^2) t_i} sum_{j>i} e^{g t_j},
        // splitting the double sum at min(t_i, t_j) = t_i; the inner sums are
        // suffix sums, so the whole moment is O(n).
        double v2 = vol * vol;
        double suffix = 0; // sum_{j>i} e^{g t_j}
        double m2 = 0;
        for (int i = n; i >= 1; i--) {
            double t = i * h;
            m2 += Math.exp((2 * g + v2) * t) + 2 * Math.exp((g + v2) * t) * suffix;
            suffix += Math.exp(g * t);
        }
        m2 *= spot * spot / ((double) n * n);
        // Matched log-variance; guard the deterministic edge (vol = 0 makes
        // M2 exactly M1^2 and the log a rounding-noise 0/0).
        double varLog = m2 > m1 * m1 ? Math.log(m2 / (m1 * m1)) : 0;
        return blackOnLognormal(type, m1, strike, varLog, rate, timeYears);
    }

    /**
     * Turnbull-Wakeman two-moment price for an arithmetic average over arbitrary
     * fixing times, some of which may already be struck.
     *
     * @param fixingTimes   remaining fixing times in years, strictly ascending,
     *                      in {@code (0, timeYears]}; may be empty when everything is fixed
     * @param timeYears     payment time (expiry), at or after the last fixing
     * @param observedSum   sum of the fixings already struck (0 if none)
     * @param observedCount number of fixings already struck (0 if none)
     */
    public static double turnbullWakemanSeasoned(BlackScholes.OptionType type, double spot,
                                                 double strike, double rate, double carry,
                                                 double vol, double[] fixingTimes,
                                                 double timeYears, double observedSum,
                                                 int observedCount) {
        validateSchedule(spot, strike, rate, carry, vol, fixingTimes, timeYears, observedSum,
                observedCount);
        int m = fixingTimes.length;
        int n = m + observedCount;
        double df = Math.exp(-rate * timeYears);
        if (m == 0) {
            return df * Math.max(0, type.sign() * (observedSum / n - strike));
        }
        double kStar = (n * strike - observedSum) / m;
        double w = (double) m / n;
        double m1 = remainingMean(spot, rate - carry, fixingTimes);
        if (kStar <= 0) {
            return type == BlackScholes.OptionType.CALL ? df * w * (m1 - kStar) : 0;
        }
        double g = rate - carry;
        double v2 = vol * vol;
        double suffix = 0;
        double m2 = 0;
        for (int i = m - 1; i >= 0; i--) {
            double t = fixingTimes[i];
            m2 += Math.exp((2 * g + v2) * t) + 2 * Math.exp((g + v2) * t) * suffix;
            suffix += Math.exp(g * t);
        }
        m2 *= spot * spot / ((double) m * m);
        double varLog = m2 > m1 * m1 ? Math.log(m2 / (m1 * m1)) : 0;
        return w * blackOnLognormal(type, m1, kStar, varLog, rate, timeYears);
    }

    /**
     * Curran (1994) arithmetic-average price by conditioning on the geometric
     * average, for arbitrary (possibly seasoned) fixing schedules. With
     * {@code mu_i = ln S + (r - q - vol^2/2) t_i}, {@code s_i^2 = vol^2 t_i},
     * {@code mu = mean(mu_i)}, {@code s_x^2 = vol^2/m^2 sum_ij min(t_i, t_j)},
     * {@code s_xi = vol^2/m sum_j min(t_i, t_j)} (Curran 1994; Haug 2007,
     * section 4.20.3):
     *
     * <pre>
     *   K^ = 2K - (1/m) sum_i exp(mu_i + s_xi (ln K - mu)/s_x^2 + (s_i^2 - s_xi^2/s_x^2)/2)
     *   C  = e^{-rT} [ (1/m) sum_i e^{mu_i + s_i^2/2} N((mu - ln K^)/s_x + s_xi/s_x) - K N((mu - ln K^)/s_x) ]
     * </pre>
     *
     * <p>applied to the remaining average and reduced strike. The put follows
     * from parity on the average. When {@code K^ <= 0} (strike far below the
     * forward average) the conditioning region is the whole space and the
     * call is priced as the discounted forward of {@code A - K}.</p>
     *
     * <p>Parameters as in {@link #turnbullWakemanSeasoned}.</p>
     */
    public static double curranPrice(BlackScholes.OptionType type, double spot, double strike,
                                     double rate, double carry, double vol,
                                     double[] fixingTimes, double timeYears, double observedSum,
                                     int observedCount) {
        validateSchedule(spot, strike, rate, carry, vol, fixingTimes, timeYears, observedSum,
                observedCount);
        int m = fixingTimes.length;
        int n = m + observedCount;
        double df = Math.exp(-rate * timeYears);
        if (m == 0) {
            return df * Math.max(0, type.sign() * (observedSum / n - strike));
        }
        double kStar = (n * strike - observedSum) / m;
        double w = (double) m / n;
        double fwdAvg = remainingMean(spot, rate - carry, fixingTimes);
        double forwardPayoff = df * (fwdAvg - kStar);          // call - put on the remaining average
        if (kStar <= 0) {
            return type == BlackScholes.OptionType.CALL ? w * forwardPayoff : 0;
        }
        double call = vol == 0
                ? df * Math.max(0, fwdAvg - kStar)
                : curranCall(spot, kStar, rate - carry, vol, fixingTimes, df, fwdAvg);
        double price = type == BlackScholes.OptionType.CALL ? call : call - forwardPayoff;
        return w * Math.max(0, price);
    }

    private static double curranCall(double spot, double k, double g, double vol, double[] t,
                                     double df, double fwdAvg) {
        int m = t.length;
        double v2 = vol * vol;
        double lnS = Math.log(spot);
        double[] sumMin = new double[m];         // sum_j min(t_i, t_j)
        double prefix = 0;
        for (int i = 0; i < m; i++) {
            prefix += t[i];
            sumMin[i] = prefix + (m - 1 - i) * t[i];
        }
        double mu = 0;
        double total = 0;
        for (int i = 0; i < m; i++) {
            mu += lnS + (g - 0.5 * v2) * t[i];
            total += sumMin[i];
        }
        mu /= m;
        double sx2 = v2 * total / ((double) m * m);
        double sx = Math.sqrt(sx2);
        double lnK = Math.log(k);
        double kHatSum = 0;
        for (int i = 0; i < m; i++) {
            double mui = lnS + (g - 0.5 * v2) * t[i];
            double si2 = v2 * t[i];
            double sxi = v2 * sumMin[i] / m;
            kHatSum += Math.exp(mui + sxi * (lnK - mu) / sx2 + 0.5 * (si2 - sxi * sxi / sx2));
        }
        double kHat = 2 * k - kHatSum / m;
        if (kHat <= 0) {
            return df * (fwdAvg - k);
        }
        double z = (mu - Math.log(kHat)) / sx;
        double first = 0;
        for (int i = 0; i < m; i++) {
            double mui = lnS + (g - 0.5 * v2) * t[i];
            double sxi = v2 * sumMin[i] / m;
            first += Math.exp(mui + 0.5 * v2 * t[i]) * MathUtils.normCdf(z + sxi / sx);
        }
        return df * (first / m - k * MathUtils.normCdf(z));
    }

    /**
     * Levy (1992) continuous-average lognormal approximation with seasoning,
     * as printed in Haug (2007, section 4.20.2). The average runs over
     * {@code averagingPeriod} years and ends at expiry; {@code timeYears}
     * remain, and the average realised so far is {@code realisedAverage}.
     * With {@code b = rate - carry}, T the averaging period and T2 the time left:
     *
     * <pre>
     *   S_E = S/(T b) (e^{(b-r)T2} - e^{-r T2})
     *   M   = 2 S^2/(b + vol^2) [ (e^{(2b+vol^2)T2} - 1)/(2b + vol^2) - (e^{b T2} - 1)/b ]
     *   D   = M / T^2,   V = ln D - 2 (r T2 + ln S_E),   X* = K - (T - T2)/T S_A
     *   d1  = (ln(D)/2 - ln X*)/sqrt V,   d2 = d1 - sqrt V
     *   C   = S_E N(d1) - X* e^{-r T2} N(d2),   P = C - S_E + X* e^{-r T2}
     * </pre>
     *
     * <p>Removable singularities at {@code b = 0} and {@code b + vol^2 = 0} are
     * evaluated by their limits. When {@code X* <= 0} the call is the
     * discounted forward {@code S_E - X* e^{-r T2}} and the put is 0.</p>
     *
     * @param averagingPeriod total averaging length T, at least {@code timeYears}
     * @param realisedAverage average of the path so far (ignored when nothing has elapsed)
     */
    public static double levyPrice(BlackScholes.OptionType type, double spot, double strike,
                                   double rate, double carry, double vol, double timeYears,
                                   double averagingPeriod, double realisedAverage) {
        validate(spot, strike, rate, carry, vol, timeYears, 1);
        if (!(averagingPeriod >= timeYears) || !Double.isFinite(averagingPeriod)) {
            throw new IllegalArgumentException("averagingPeriod must be finite and >= timeYears");
        }
        double elapsed = averagingPeriod - timeYears;
        if (elapsed > 0 && !(realisedAverage >= 0 && Double.isFinite(realisedAverage))) {
            throw new IllegalArgumentException("realisedAverage must be finite and >= 0");
        }
        double b = rate - carry;
        double t2 = timeYears;
        double bigT = averagingPeriod;
        double v2 = vol * vol;
        double se = spot * Math.exp(-rate * t2) * e1(b, t2) / bigT;
        double m = 2 * spot * spot * divided(2 * b + v2, b, t2);
        double dd = m / (bigT * bigT);
        double xStar = strike - (elapsed > 0 ? elapsed / bigT * realisedAverage : 0);
        double dfx = Math.exp(-rate * t2);
        if (xStar <= 0) {
            return type == BlackScholes.OptionType.CALL ? se - xStar * dfx : 0;
        }
        double var = Math.log(dd) - 2 * (rate * t2 + Math.log(se));
        double call;
        if (!(var > 1e-300)) {
            call = Math.max(0, se - xStar * dfx);
        } else {
            double sv = Math.sqrt(var);
            double d1 = (0.5 * Math.log(dd) - Math.log(xStar)) / sv;
            double d2 = d1 - sv;
            call = se * MathUtils.normCdf(d1) - xStar * dfx * MathUtils.normCdf(d2);
        }
        double price = type == BlackScholes.OptionType.CALL ? call : call - se + xStar * dfx;
        return Math.max(0, price);
    }

    /** (e^{x t} - 1)/x, equal to t at x = 0. */
    private static double e1(double x, double t) {
        return x == 0 ? t : Math.expm1(x * t) / x;
    }

    /** Divided difference (e1(x) - e1(y))/(x - y), or its limit e1' when x is within 1e-6 of y. */
    private static double divided(double x, double y, double t) {
        double h = x - y;
        if (Math.abs(h) > 1e-6 * Math.max(1, Math.abs(y))) {
            return (e1(x, t) - e1(y, t)) / h;
        }
        // Limit e1'(z) = integral_0^t s e^{z s} ds at the midpoint z, Simpson on a smooth integrand.
        int n = 64;
        double step = t / n;
        double sum = 0;
        for (int i = 0; i <= n; i++) {
            double s = i * step;
            double wt = (i == 0 || i == n) ? 1 : ((i & 1) == 1 ? 4 : 2);
            sum += wt * s * Math.exp(0.5 * (x + y) * s);
        }
        return sum * step / 3;
    }

    private static double remainingMean(double spot, double g, double[] t) {
        double s = 0;
        for (double ti : t) {
            s += Math.exp(g * ti);
        }
        return spot * s / t.length;
    }

    private static void validateSchedule(double spot, double strike, double rate, double carry,
                                         double vol, double[] fixingTimes, double timeYears,
                                         double observedSum, int observedCount) {
        validate(spot, strike, rate, carry, vol, timeYears, 1);
        if (observedCount < 0 || !(observedSum >= 0) || !Double.isFinite(observedSum)
                || (observedCount == 0 && observedSum != 0)) {
            throw new IllegalArgumentException("observed count and sum must be >= 0, sum 0 when count 0");
        }
        if (fixingTimes.length + observedCount < 1) {
            throw new IllegalArgumentException("need at least one fixing");
        }
        for (int i = 0; i < fixingTimes.length; i++) {
            double t = fixingTimes[i];
            if (!(t > 0) || t > timeYears || (i > 0 && !(t > fixingTimes[i - 1]))) {
                throw new IllegalArgumentException(
                        "fixing times must be strictly ascending in (0, timeYears]");
            }
        }
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
