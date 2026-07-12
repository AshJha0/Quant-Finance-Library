package com.quantfinlib.volatility;

import com.quantfinlib.core.BarSeries;

/**
 * RANGE-BASED volatility estimators — the free lunch hiding inside every
 * OHLC bar: the high-low range carries far more information about the
 * day's variance than the close alone, so a range estimator reaches a
 * given precision with several times fewer bars than close-to-close.
 * Four classics, in increasing order of what they use:
 *
 * <ul>
 *   <li><b>Parkinson (1980)</b> — range only:
 *       {@code sigma^2 = mean( ln(H/L)^2 ) / (4 ln 2)}. About 4.9x more
 *       efficient than close-to-close under driftless GBM; biased UP by
 *       drift (it books trend as range) and DOWN by discrete sampling
 *       (the observed high underestimates the true high).</li>
 *   <li><b>Garman-Klass (1980)</b> — range plus open/close:
 *       {@code sigma^2 = mean( 0.5 ln(H/L)^2 - (2 ln 2 - 1) ln(C/O)^2 )}.
 *       Roughly 7.4x efficient; still assumes zero drift.</li>
 *   <li><b>Rogers-Satchell (1991)</b> — drift-INDEPENDENT:
 *       {@code sigma^2 = mean( ln(H/C) ln(H/O) + ln(L/C) ln(L/O) )}.
 *       The one to reach for on trending series.</li>
 *   <li><b>Yang-Zhang (2000)</b> — adds the OVERNIGHT gap the others
 *       ignore: {@code sigma^2 = sigma_o^2 + k sigma_c^2 + (1-k) sigma_rs^2}
 *       with {@code sigma_o^2} the sample variance of open-over-prior-close
 *       log returns, {@code sigma_c^2} the sample variance of close-over-open
 *       log returns, {@code sigma_rs^2} the Rogers-Satchell term, and
 *       {@code k = 0.34 / (1.34 + (m+1)/(m-1))} over {@code m} periods —
 *       the weighting that minimizes the estimator's variance. Drift
 *       independent AND jump-aware; the practical default for daily bars
 *       on markets that close.</li>
 * </ul>
 *
 * <p>All methods return ANNUALIZED volatility (not variance):
 * {@code sqrt(perPeriodVariance * periodsPerYear)}, with the annualization
 * factor supplied by the caller (252 for daily bars, 52 for weekly, ...) —
 * this class does not choose your calendar. Estimates are computed over
 * the full arrays passed in; slice ({@link BarSeries#slice}) for rolling
 * windows. Static, deterministic, research lane.</p>
 */
public final class RangeVolatility {

    private static final double FOUR_LN_2 = 4 * Math.log(2);
    private static final double GK_CLOSE_WEIGHT = 2 * Math.log(2) - 1;

    private RangeVolatility() {
    }

    /** Parkinson estimator from highs/lows, annualized. */
    public static double parkinson(double[] high, double[] low, double periodsPerYear) {
        checkAligned(high.length, low.length, 1);
        checkPeriods(periodsPerYear);
        double sum = 0;
        for (int i = 0; i < high.length; i++) {
            checkBar(high[i], high[i], low[i], high[i], i);   // H/L only
            double hl = Math.log(high[i] / low[i]);
            sum += hl * hl;
        }
        return Math.sqrt(sum / high.length / FOUR_LN_2 * periodsPerYear);
    }

    /** Parkinson over a whole {@link BarSeries}. */
    public static double parkinson(BarSeries bars, double periodsPerYear) {
        return parkinson(bars.highs(), bars.lows(), periodsPerYear);
    }

    /** Garman-Klass estimator, annualized. */
    public static double garmanKlass(double[] open, double[] high, double[] low,
                                     double[] close, double periodsPerYear) {
        checkOhlc(open, high, low, close, 1);
        checkPeriods(periodsPerYear);
        double sum = 0;
        for (int i = 0; i < open.length; i++) {
            double hl = Math.log(high[i] / low[i]);
            double co = Math.log(close[i] / open[i]);
            sum += 0.5 * hl * hl - GK_CLOSE_WEIGHT * co * co;
        }
        // The close term can push a single bar negative; the average of a
        // valid sample cannot reasonably be, but floor for safety.
        return Math.sqrt(Math.max(0, sum / open.length) * periodsPerYear);
    }

    /** Garman-Klass over a whole {@link BarSeries}. */
    public static double garmanKlass(BarSeries bars, double periodsPerYear) {
        return garmanKlass(bars.opens(), bars.highs(), bars.lows(), bars.closes(), periodsPerYear);
    }

    /** Rogers-Satchell (drift-independent) estimator, annualized. */
    public static double rogersSatchell(double[] open, double[] high, double[] low,
                                        double[] close, double periodsPerYear) {
        checkOhlc(open, high, low, close, 1);
        checkPeriods(periodsPerYear);
        double sum = 0;
        for (int i = 0; i < open.length; i++) {
            sum += rsTerm(open[i], high[i], low[i], close[i]);
        }
        return Math.sqrt(sum / open.length * periodsPerYear);
    }

    /** Rogers-Satchell over a whole {@link BarSeries}. */
    public static double rogersSatchell(BarSeries bars, double periodsPerYear) {
        return rogersSatchell(bars.opens(), bars.highs(), bars.lows(), bars.closes(),
                periodsPerYear);
    }

    /**
     * Yang-Zhang estimator, annualized. Uses bars {@code 1..n-1} as the
     * estimation periods (bar 0 only supplies the prior close for the first
     * overnight return), so it needs at least 3 bars for the sample
     * variances to exist.
     */
    public static double yangZhang(double[] open, double[] high, double[] low,
                                   double[] close, double periodsPerYear) {
        checkOhlc(open, high, low, close, 3);
        checkPeriods(periodsPerYear);
        int m = open.length - 1;   // estimation periods
        // Overnight (close-to-open) and open-to-close log returns.
        double meanO = 0;
        double meanC = 0;
        for (int i = 1; i <= m; i++) {
            meanO += Math.log(open[i] / close[i - 1]);
            meanC += Math.log(close[i] / open[i]);
        }
        meanO /= m;
        meanC /= m;
        double varO = 0;
        double varC = 0;
        double rs = 0;
        for (int i = 1; i <= m; i++) {
            double o = Math.log(open[i] / close[i - 1]) - meanO;
            double c = Math.log(close[i] / open[i]) - meanC;
            varO += o * o;
            varC += c * c;
            rs += rsTerm(open[i], high[i], low[i], close[i]);
        }
        varO /= m - 1;
        varC /= m - 1;
        rs /= m;
        double k = 0.34 / (1.34 + (m + 1.0) / (m - 1.0));
        return Math.sqrt((varO + k * varC + (1 - k) * rs) * periodsPerYear);
    }

    /** Yang-Zhang over a whole {@link BarSeries}. */
    public static double yangZhang(BarSeries bars, double periodsPerYear) {
        return yangZhang(bars.opens(), bars.highs(), bars.lows(), bars.closes(), periodsPerYear);
    }

    // ------------------------------------------------------------------

    private static double rsTerm(double o, double h, double l, double c) {
        return Math.log(h / c) * Math.log(h / o) + Math.log(l / c) * Math.log(l / o);
    }

    private static void checkOhlc(double[] open, double[] high, double[] low,
                                  double[] close, int minBars) {
        checkAligned(open.length, high.length, minBars);
        checkAligned(open.length, low.length, minBars);
        checkAligned(open.length, close.length, minBars);
        for (int i = 0; i < open.length; i++) {
            checkBar(open[i], high[i], low[i], close[i], i);
        }
    }

    private static void checkAligned(int a, int b, int minBars) {
        if (a != b) {
            throw new IllegalArgumentException("O/H/L/C arrays must be aligned: " + a + " vs " + b);
        }
        if (a < minBars) {
            throw new IllegalArgumentException("need >= " + minBars + " bars, got " + a);
        }
    }

    private static void checkBar(double o, double h, double l, double c, int i) {
        // NaN-rejecting: any NaN fails one of these comparisons.
        if (!(l > 0) || !(h >= l) || h == Double.POSITIVE_INFINITY
                || !(o >= l) || !(o <= h) || !(c >= l) || !(c <= h)) {
            throw new IllegalArgumentException("bar " + i + " violates H >= O,C >= L > 0: O=" + o
                    + " H=" + h + " L=" + l + " C=" + c);
        }
    }

    private static void checkPeriods(double periodsPerYear) {
        if (!(periodsPerYear > 0) || periodsPerYear == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException(
                    "periodsPerYear must be positive and finite, got " + periodsPerYear);
        }
    }
}
