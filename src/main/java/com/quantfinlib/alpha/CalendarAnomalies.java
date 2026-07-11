package com.quantfinlib.alpha;

import com.quantfinlib.util.MathUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Calendar anomaly profiles — day-of-week and turn-of-month seasonality
 * with the t-statistics that keep them honest. The honest part is the
 * POINT: most published calendar anomalies (the weekend effect, the
 * January effect) decayed or died after publication, and the difference
 * between a tradable seasonal and a data-mined ghost is a t-stat that
 * survives out of sample. This class hands you the profile AND the
 * significance; treat |t| &lt; 2 as decoration, and re-test out of
 * sample before believing anything (see {@code AlphaValidation}).
 *
 * <p>Turn-of-month windows use CALENDAR days of month (last
 * {@code daysBeforeMonthEnd} calendar days + first
 * {@code daysAfterMonthStart}), not trading days — stated, not hidden;
 * with daily equity data the difference is a day around holidays.
 * Timestamps are interpreted in UTC. Static, deterministic, research
 * lane.</p>
 */
public final class CalendarAnomalies {

    /**
     * Per-day-of-week profile, indexed Monday = 0 … Sunday = 6. Days
     * with no observations report NaN mean and t.
     */
    public record DayOfWeekProfile(double[] meanReturn, double[] tStat, int[] count) {
    }

    /** The turn-of-month split, with a Welch t-stat on the difference. */
    public record TurnOfMonth(double insideMean, double outsideMean, double tStat,
                              int insideCount, int outsideCount) {
    }

    private CalendarAnomalies() {
    }

    /** @param returns per-period returns aligned with {@code epochMillis} */
    public static DayOfWeekProfile dayOfWeek(double[] returns, long[] epochMillis) {
        requireAligned(returns, epochMillis);
        double[][] byDay = new double[7][returns.length];
        int[] counts = new int[7];
        for (int i = 0; i < returns.length; i++) {
            int d = toDate(epochMillis[i]).getDayOfWeek().getValue() - 1;
            byDay[d][counts[d]++] = returns[i];
        }
        double[] mean = new double[7];
        double[] t = new double[7];
        for (int d = 0; d < 7; d++) {
            if (counts[d] < 2) {
                mean[d] = counts[d] == 1 ? byDay[d][0] : Double.NaN;
                t[d] = Double.NaN;
                continue;
            }
            double m = MathUtils.mean(byDay[d], 0, counts[d]);
            double se = MathUtils.stdDevSample(byDay[d], 0, counts[d])
                    / Math.sqrt(counts[d]);
            mean[d] = m;
            t[d] = se > 0 ? m / se : Math.signum(m) * Double.POSITIVE_INFINITY;
        }
        return new DayOfWeekProfile(mean, t, counts);
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
        int nIn = 0;
        int nOut = 0;
        for (int i = 0; i < returns.length; i++) {
            LocalDate d = toDate(epochMillis[i]);
            boolean in = d.getDayOfMonth() <= daysAfterMonthStart
                    || d.getDayOfMonth() > d.lengthOfMonth() - daysBeforeMonthEnd;
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
        double t = se > 0 ? (mIn - mOut) / se
                : Math.signum(mIn - mOut) * Double.POSITIVE_INFINITY;
        return new TurnOfMonth(mIn, mOut, t, nIn, nOut);
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
