package com.quantfinlib.alpha;

import com.quantfinlib.util.MathUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Calendar anomaly profiles: day-of-week and turn-of-month seasonality
 * with their t-statistics. Most published calendar anomalies (the weekend
 * effect, the January effect) decayed after publication, and what separates
 * a tradable seasonal from a data-mined one is a t-stat that survives out
 * of sample. Treat |t| &lt; 2 as decoration and re-test out of sample before
 * believing anything (see {@code AlphaValidation}).
 *
 * <p>Significance. Daily returns cluster in volatility, so iid standard
 * errors understate uncertainty. Both profiles report Newey-West (1987)
 * HAC t-stats from a dummy regression on the full return series (lag from
 * the Andrews 1991 AR(1) plug-in on the returns), with the iid t kept as
 * {@code naiveTStat}. Testing five to seven days at once, the best of them
 * clears |t| &gt; 2 by chance far more often than 5% of the time, so the
 * day-of-week profile also flags which days survive a Holm (1979,
 * Scand. J. Statistics 6(2)) step-down correction at the 5% family-wise
 * level, using normal two-sided p-values from the HAC t.</p>
 *
 * <p>Turn-of-month windows use calendar days of month (last
 * {@code daysBeforeMonthEnd} calendar days + first
 * {@code daysAfterMonthStart}), not trading days; with daily equity data the
 * difference is a day around holidays. Timestamps are interpreted in UTC.
 * Static, deterministic, research lane.</p>
 */
public final class CalendarAnomalies {

    /** Family-wise error rate used for the Holm flags. */
    public static final double FAMILY_WISE_ALPHA = 0.05;

    /**
     * Per-day-of-week profile, indexed Monday = 0 … Sunday = 6. Days with
     * fewer than two observations report NaN t-stats and are not tested.
     *
     * @param tStat          Newey-West t-stat of each day's mean
     * @param naiveTStat     iid t-stat (mean over sample-sd/√n)
     * @param holmSignificant day survives Holm's correction across the tested days
     */
    public record DayOfWeekProfile(double[] meanReturn, double[] tStat, int[] count,
                                   double[] naiveTStat, boolean[] holmSignificant, int hacLag) {
    }

    /**
     * The turn-of-month split.
     *
     * @param tStat      Newey-West t-stat of the inside-minus-outside difference
     * @param welchTStat iid Welch t-stat of the same difference
     */
    public record TurnOfMonth(double insideMean, double outsideMean, double tStat,
                              int insideCount, int outsideCount, double welchTStat, int hacLag) {
    }

    private CalendarAnomalies() {
    }

    /** @param returns per-period returns aligned with {@code epochMillis} */
    public static DayOfWeekProfile dayOfWeek(double[] returns, long[] epochMillis) {
        requireAligned(returns, epochMillis);
        int n = returns.length;
        int[] dayOf = new int[n];
        double[][] byDay = new double[7][n];
        int[] counts = new int[7];
        for (int i = 0; i < n; i++) {
            int d = toDate(epochMillis[i]).getDayOfWeek().getValue() - 1;
            dayOf[i] = d;
            byDay[d][counts[d]++] = returns[i];
        }
        double[] mean = new double[7];
        double[] naive = new double[7];
        double[] hacT = new double[7];
        java.util.Arrays.fill(hacT, Double.NaN);
        int[] column = new int[7];
        int tested = 0;
        for (int d = 0; d < 7; d++) {
            column[d] = -1;
            if (counts[d] < 2) {
                mean[d] = counts[d] == 1 ? byDay[d][0] : Double.NaN;
                naive[d] = Double.NaN;
                continue;
            }
            double m = MathUtils.mean(byDay[d], 0, counts[d]);
            double se = MathUtils.stdDevSample(byDay[d], 0, counts[d]) / Math.sqrt(counts[d]);
            mean[d] = m;
            naive[d] = se > 0 ? m / se : Math.signum(m) * Double.POSITIVE_INFINITY;
            column[d] = tested++;
        }
        // Dummy regression (no intercept) over observations on tested days.
        int rows = 0;
        for (int i = 0; i < n; i++) {
            if (column[dayOf[i]] >= 0) {
                rows++;
            }
        }
        double[][] x = new double[rows][tested];
        double[] y = new double[rows];
        int r = 0;
        for (int i = 0; i < n; i++) {
            if (column[dayOf[i]] >= 0) {
                x[r][column[dayOf[i]]] = 1;
                y[r++] = returns[i];
            }
        }
        int lag = NeweyWest.automaticLag(y);
        Ols.Fit fit = Ols.fit(x, y, lag);
        double[] p = new double[tested];
        for (int d = 0; d < 7; d++) {
            if (column[d] >= 0) {
                hacT[d] = fit.hacTStat(column[d]);
                p[column[d]] = 2 * (1 - MathUtils.normCdf(Math.abs(hacT[d])));
            }
        }
        boolean[] holmCol = holm(p, FAMILY_WISE_ALPHA);
        boolean[] significant = new boolean[7];
        for (int d = 0; d < 7; d++) {
            significant[d] = column[d] >= 0 && holmCol[column[d]];
        }
        return new DayOfWeekProfile(mean, hacT, counts, naive, significant, lag);
    }

    /**
     * Holm step-down: sort p ascending, reject while
     * {@code p_(k) <= alpha/(m − k)} (k zero-based), stop at the first failure.
     */
    static boolean[] holm(double[] p, double alpha) {
        int m = p.length;
        Integer[] order = new Integer[m];
        for (int i = 0; i < m; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> Double.compare(p[a], p[b]));
        boolean[] reject = new boolean[m];
        for (int k = 0; k < m; k++) {
            if (p[order[k]] <= alpha / (m - k)) {
                reject[order[k]] = true;
            } else {
                break;
            }
        }
        return reject;
    }

    /**
     * @param daysBeforeMonthEnd  calendar days at month end in the
     *                            window, ≥ 0
     * @param daysAfterMonthStart calendar days at month start in the
     *                            window, ≥ 0 (at least one of the two &gt; 0)
     */
    public static TurnOfMonth turnOfMonth(double[] returns, long[] epochMillis,
                                          int daysBeforeMonthEnd, int daysAfterMonthStart) {
        requireAligned(returns, epochMillis);
        if (daysBeforeMonthEnd < 0 || daysAfterMonthStart < 0
                || daysBeforeMonthEnd + daysAfterMonthStart == 0) {
            throw new IllegalArgumentException("the window must cover at least one day");
        }
        double[] inside = new double[returns.length];
        double[] outside = new double[returns.length];
        double[][] x = new double[returns.length][2];
        int nIn = 0;
        int nOut = 0;
        for (int i = 0; i < returns.length; i++) {
            LocalDate d = toDate(epochMillis[i]);
            boolean in = d.getDayOfMonth() <= daysAfterMonthStart
                    || d.getDayOfMonth() > d.lengthOfMonth() - daysBeforeMonthEnd;
            x[i][0] = 1;
            x[i][1] = in ? 1 : 0;
            if (in) {
                inside[nIn++] = returns[i];
            } else {
                outside[nOut++] = returns[i];
            }
        }
        if (nIn < 2 || nOut < 2) {
            throw new IllegalArgumentException("both windows need >= 2 observations (inside "
                    + nIn + ", outside " + nOut + ")");
        }
        double mIn = MathUtils.mean(inside, 0, nIn);
        double mOut = MathUtils.mean(outside, 0, nOut);
        double vIn = square(MathUtils.stdDevSample(inside, 0, nIn));
        double vOut = square(MathUtils.stdDevSample(outside, 0, nOut));
        double se = Math.sqrt(vIn / nIn + vOut / nOut);
        double welch = se > 0 ? (mIn - mOut) / se
                : Math.signum(mIn - mOut) * Double.POSITIVE_INFINITY;
        int lag = NeweyWest.automaticLag(returns);
        double hacT = Ols.fit(x, returns, lag).hacTStat(1);
        return new TurnOfMonth(mIn, mOut, hacT, nIn, nOut, welch, lag);
    }

    private static LocalDate toDate(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static double square(double x) {
        return x * x;
    }

    private static void requireAligned(double[] returns, long[] epochMillis) {
        if (returns.length != epochMillis.length || returns.length < 30) {
            throw new IllegalArgumentException("need >= 30 aligned observations");
        }
        for (double r : returns) {
            if (!Double.isFinite(r)) {
                throw new IllegalArgumentException("returns must be finite");
            }
        }
    }
}
