package com.quantfinlib.markets;

/**
 * PRIVATE-MARKET analytics — the toolkit for the asset class where the
 * usual machinery fails on purpose: no daily prices, cash flows the
 * manager (not the investor) times, and NAVs that are appraisals rather
 * than trades.
 *
 * <ul>
 *   <li><b>IRR</b> — the money-weighted return: the rate that zeroes the
 *       NPV of the fund's cash flows plus terminal NAV. It rewards the
 *       manager's TIMING (which a time-weighted return deliberately
 *       ignores), which is why PE quotes IRR and mutual funds may not.
 *       Solved by bisection with an explicit sign-change/bracket check —
 *       cash flows that never change sign have no IRR, and this throws
 *       rather than inventing one.</li>
 *   <li><b>Multiples</b> — TVPI = (distributions + NAV)/contributions,
 *       DPI = distributions/contributions (the "cash back" ratio),
 *       RVPI = NAV/contributions (the part still an appraisal). DPI is
 *       the honest one: you cannot spend RVPI.</li>
 *   <li><b>Kaplan-Schoar PME</b> — the public-market equivalent: grow
 *       every contribution and distribution forward at the INDEX's
 *       return and take {@code (FV(distributions) + NAV) /
 *       FV(contributions)}. PME &gt; 1 means the fund beat just buying
 *       the index with the same cash flows on the same dates — the only
 *       fair benchmark for irregular cash flows, and the reason "our IRR
 *       beat the S&amp;P's return" is not evidence.</li>
 *   <li><b>Geltner desmoothing</b> — appraisal NAVs are AR(1)-smoothed
 *       versions of true returns ({@code r_obs_t = (1-phi) r_true_t +
 *       phi r_obs_{t-1}}), which UNDERSTATES volatility and correlation
 *       to public markets ("volatility laundering"). Inverting,
 *       {@code r_true_t = (r_obs_t - phi r_obs_{t-1}) / (1 - phi)},
 *       recovers a series whose risk numbers can sit honestly next to
 *       public-market ones. The inversion is exact: smoothing then
 *       desmoothing round-trips to machine precision (pinned).</li>
 * </ul>
 *
 * <p>Cash-flow sign convention throughout: contributions (money in)
 * NEGATIVE, distributions (money out) POSITIVE — the investor's
 * perspective, matching every spreadsheet's XIRR. Period-indexed flows
 * (annual/quarterly — caller's choice, IRR is per period). Research
 * lane, deterministic.</p>
 */
public final class PrivateMarketAnalytics {

    private PrivateMarketAnalytics() {
    }

    /**
     * Money-weighted return per period: solves
     * {@code sum cf_t / (1+irr)^t = 0}. The final period's cash flow
     * should include terminal NAV as a distribution.
     *
     * @param cashflows period-indexed, index 0 = today; must contain at
     *                  least one negative and one positive flow
     */
    public static double irr(double[] cashflows) {
        if (cashflows.length < 2) {
            throw new IllegalArgumentException("need >= 2 cash flows, got " + cashflows.length);
        }
        boolean hasNeg = false, hasPos = false;
        for (double cf : cashflows) {
            if (!Double.isFinite(cf)) {
                throw new IllegalArgumentException("non-finite cash flow");
            }
            hasNeg |= cf < 0;
            hasPos |= cf > 0;
        }
        if (!hasNeg || !hasPos) {
            throw new IllegalArgumentException(
                    "cash flows never change sign: no IRR exists");
        }
        double lo = -0.9999, hi = 100;
        double npvLo = npv(cashflows, lo);
        double npvHi = npv(cashflows, hi);
        if (npvLo * npvHi > 0) {
            throw new IllegalArgumentException("no IRR in (-99.99%, 10000%)");
        }
        for (int i = 0; i < 300; i++) {
            double mid = 0.5 * (lo + hi);
            double v = npv(cashflows, mid);
            if (v * npvLo > 0) {
                lo = mid;
                npvLo = v;
            } else {
                hi = mid;
            }
        }
        return 0.5 * (lo + hi);
    }

    /** TVPI: total value (distributions + NAV) to paid-in. */
    public static double tvpi(double contributions, double distributions, double nav) {
        validateMultiples(contributions, distributions, nav);
        return (distributions + nav) / contributions;
    }

    /** DPI: realized distributions to paid-in — the cash-back multiple. */
    public static double dpi(double contributions, double distributions, double nav) {
        validateMultiples(contributions, distributions, nav);
        return distributions / contributions;
    }

    /** RVPI: remaining (appraised) value to paid-in. */
    public static double rvpi(double contributions, double distributions, double nav) {
        validateMultiples(contributions, distributions, nav);
        return nav / contributions;
    }

    /**
     * Kaplan-Schoar PME. Arrays are period-aligned with
     * {@code indexLevels} (same length); contributions/distributions are
     * the POSITIVE amounts flowing in each period.
     *
     * @return PME; &gt; 1 = fund beat the index on its own cash-flow dates
     */
    public static double ksPme(double[] contributions, double[] distributions,
                               double terminalNav, double[] indexLevels) {
        int n = indexLevels.length;
        if (n < 2 || contributions.length != n || distributions.length != n) {
            throw new IllegalArgumentException("need aligned series of length >= 2");
        }
        double last = indexLevels[n - 1];
        double fvContrib = 0, fvDistrib = 0;
        for (int t = 0; t < n; t++) {
            if (!(indexLevels[t] > 0) || indexLevels[t] == Double.POSITIVE_INFINITY) {
                throw new IllegalArgumentException("index level must be positive: " + indexLevels[t]);
            }
            if (!(contributions[t] >= 0) || !(distributions[t] >= 0)
                    || contributions[t] == Double.POSITIVE_INFINITY
                    || distributions[t] == Double.POSITIVE_INFINITY) {
                throw new IllegalArgumentException("flows must be >= 0 and finite (amounts, not signed)");
            }
            double growth = last / indexLevels[t];
            fvContrib += contributions[t] * growth;
            fvDistrib += distributions[t] * growth;
        }
        if (!(terminalNav >= 0) || terminalNav == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("terminalNav must be >= 0 and finite");
        }
        if (fvContrib == 0) {
            throw new IllegalArgumentException("no contributions: PME undefined");
        }
        return (fvDistrib + terminalNav) / fvContrib;
    }

    /**
     * Geltner desmoothing: inverts AR(1) appraisal smoothing with
     * parameter {@code phi} in [0, 1). Element 0 is kept as observed
     * (no lag exists for it).
     */
    public static double[] geltnerDesmooth(double[] observedReturns, double phi) {
        if (observedReturns.length < 2) {
            throw new IllegalArgumentException("need >= 2 returns");
        }
        if (!(phi >= 0) || !(phi < 1)) {
            throw new IllegalArgumentException("phi must be in [0, 1), got " + phi);
        }
        double[] r = new double[observedReturns.length];
        r[0] = requireFinite(observedReturns[0]);
        for (int t = 1; t < r.length; t++) {
            double now = requireFinite(observedReturns[t]);
            r[t] = (now - phi * observedReturns[t - 1]) / (1 - phi);
        }
        return r;
    }

    private static double npv(double[] cashflows, double rate) {
        double v = 0;
        for (int t = 0; t < cashflows.length; t++) {
            v += cashflows[t] / Math.pow(1 + rate, t);
        }
        return v;
    }

    private static void validateMultiples(double contributions, double distributions, double nav) {
        if (!(contributions > 0) || contributions == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("contributions must be positive and finite");
        }
        if (!(distributions >= 0) || distributions == Double.POSITIVE_INFINITY
                || !(nav >= 0) || nav == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("distributions and nav must be >= 0 and finite");
        }
    }

    private static double requireFinite(double v) {
        if (!Double.isFinite(v)) {
            throw new IllegalArgumentException("non-finite return");
        }
        return v;
    }
}
