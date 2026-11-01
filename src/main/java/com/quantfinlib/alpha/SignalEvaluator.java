package com.quantfinlib.alpha;

import com.quantfinlib.util.MathUtils;

import java.util.Locale;

/**
 * Signal evaluation: the metrics that decide whether a factor is worth
 * building a portfolio from, computed before any backtest so weak signals
 * die cheaply.
 *
 * <ul>
 *   <li><b>IC</b> (information coefficient): Spearman rank correlation
 *       between scores at {@code t} and forward returns over
 *       {@code (t, t+h]}. Rank rather than Pearson because scores have
 *       arbitrary units and fat tails; rank IC is invariant to any monotone
 *       transform of the signal. Computed at <em>every</em> bar from
 *       {@code startIndex}, so the forward windows overlap.</li>
 *   <li><b>t-stat</b>: {@code mean(IC) / SE_HAC}, with the Newey-West
 *       (1987) standard error at lag {@code max(h − 1, Andrews(1991) AR(1)
 *       plug-in)}. Overlapping windows make the IC series MA(h−1)
 *       mechanically, and persistent signals add positive autocorrelation
 *       on top; the iid {@code std/√n} t-stat ignores both and is reported
 *       only as {@code naiveTStat} for comparison.</li>
 *   <li><b>IR</b>: {@code mean(IC) / std(IC)}, Grinold and Kahn's signal
 *       quality per unit of inconsistency.</li>
 *   <li><b>IC autocorrelation</b>: the lag-{@code h} autocorrelation of the
 *       IC series, the correlation between consecutive non-overlapping
 *       ICs. Near 1 means the sample holds far fewer independent
 *       observations than dates.</li>
 *   <li><b>Hit rate</b>: fraction of (symbol, date) pairs where the sign of
 *       the cross-sectionally demeaned score matches the sign of the
 *       demeaned forward return. Demeaning matters: a one-signed raw score
 *       ({@code −σ} for low volatility, a yield for value) would otherwise
 *       turn the hit rate into the fraction of up-moves in the panel.</li>
 *   <li><b>Turnover</b>: half the L1 change in gross-1 z-score weights
 *       between {@code t − h} and {@code t}, the trading the signal demands
 *       if rebalanced every {@code h} bars.</li>
 *   <li><b>Factor exposure</b>: mean cross-sectional rank correlation
 *       against another factor.</li>
 * </ul>
 *
 * <p>Forward returns come from {@link AlphaContext#returnOver}, which is
 * terminal-event aware when a point-in-time universe is attached: a name
 * that delists inside the window contributes its delisting return, not the
 * flat forward-filled ghost bars that follow.</p>
 */
public final class SignalEvaluator {

    /**
     * The evaluation scorecard; {@link #format} renders it.
     *
     * @param tStat             HAC (Newey-West) t-stat of the mean IC
     * @param naiveTStat        iid t-stat {@code mean/(std/√n)}, for comparison only
     * @param hacLag            Bartlett lag used for {@code tStat}
     * @param icAutocorrelation lag-{@code horizon} autocorrelation of the IC
     *                          series (NaN when the series is too short)
     * @param horizon           forward-return horizon in bars
     * @param icSeries          per-date ICs, one per scored bar
     */
    public record Report(String factorName, double meanIc, double icStd, double ir,
                         double tStat, double naiveTStat, int hacLag, double icAutocorrelation,
                         double hitRate, double meanTurnover, int observations, int horizon,
                         double[] icSeries) {

        /** One-line human summary in fixed order for report diffs. */
        public String format() {
            return String.format(Locale.ROOT,
                    "%s: IC=%.4f (t_NW=%.2f lag %d, t_iid=%.2f, n=%d, h=%d, acf_h=%.2f) IR=%.2f"
                            + " hit=%.1f%% turnover=%.1f%%",
                    factorName, meanIc, tStat, hacLag, naiveTStat, observations, horizon,
                    icAutocorrelation, ir, hitRate * 100, meanTurnover * 100);
        }
    }

    private SignalEvaluator() {
    }

    /**
     * Evaluates a factor at every bar in {@code [startIndex, ctx.bars() − horizon)}
     * against overlapping {@code horizon}-bar forward returns (see the class
     * doc for each metric).
     *
     * @param horizon forward-return horizon in bars
     * @throws IllegalArgumentException with fewer than {@code horizon + 2}
     *                                  scored dates
     */
    public static Report evaluate(AlphaContext ctx, AlphaFactor factor,
                                  int startIndex, int horizon) {
        if (horizon <= 0 || startIndex < 0) {
            throw new IllegalArgumentException("need horizon > 0 and startIndex >= 0");
        }
        int maxDates = Math.max(0, ctx.bars() - horizon - startIndex);
        double[] icBuf = new double[maxDates];
        int n = 0;
        double hits = 0;
        double hitPairs = 0;
        double turnoverSum = 0;
        int turnoverCount = 0;
        // Ring of the last `horizon` dates' weights, for turnover at cadence h.
        double[][] ring = new double[horizon][];

        for (int t = startIndex; t + horizon < ctx.bars(); t++) {
            double[] scores = factor.scores(ctx, t);
            double[] fwd = forwardReturns(ctx, t, horizon);
            int slot = t % horizon;
            double ic = spearman(scores, fwd);
            if (Double.isNaN(ic)) {
                ring[slot] = null; // unscored date: in no metric's denominator
                continue;
            }
            icBuf[n++] = ic;
            double[] hp = demeanedHits(scores, fwd);
            hits += hp[0];
            hitPairs += hp[1];
            double[] w = PortfolioConstruction.zScoreWeights(scores, 1.0);
            double[] prev = ring[slot];
            if (prev != null) {
                double l1 = 0;
                for (int i = 0; i < w.length; i++) {
                    l1 += Math.abs(w[i] - prev[i]);
                }
                turnoverSum += l1 / 2; // buys and sells each counted once
                turnoverCount++;
            }
            ring[slot] = w;
        }
        if (n < horizon + 2 || n < 3) {
            throw new IllegalArgumentException("only " + n + " IC observations at horizon "
                    + horizon + "; extend the sample or shrink the horizon");
        }
        double[] icArr = java.util.Arrays.copyOf(icBuf, n);
        double mean = MathUtils.mean(icArr);
        double std = MathUtils.stdDev(icArr);
        int lag = NeweyWest.lagFor(icArr, horizon);
        double naive = std == 0 ? 0 : mean / (std / Math.sqrt(n));
        double acf = n > horizon + 1 ? NeweyWest.autocorrelation(icArr, horizon) : Double.NaN;
        return new Report(factor.name(), mean, std, std == 0 ? 0 : mean / std,
                NeweyWest.tStat(icArr, lag), naive, lag, acf,
                hitPairs == 0 ? Double.NaN : hits / hitPairs,
                turnoverCount == 0 ? 0 : turnoverSum / turnoverCount,
                n, horizon, icArr);
    }

    /**
     * Sign agreement of cross-sectionally demeaned scores and forward
     * returns over pairwise-complete entries: {hits, pairs}. Pairs where
     * either demeaned value is exactly zero carry no sign and are skipped.
     */
    static double[] demeanedHits(double[] scores, double[] fwd) {
        double ms = 0;
        double mf = 0;
        int k = 0;
        for (int i = 0; i < scores.length; i++) {
            if (!Double.isNaN(scores[i]) && !Double.isNaN(fwd[i])) {
                ms += scores[i];
                mf += fwd[i];
                k++;
            }
        }
        if (k < 2) {
            return new double[2];
        }
        ms /= k;
        mf /= k;
        double hits = 0;
        double pairs = 0;
        for (int i = 0; i < scores.length; i++) {
            if (Double.isNaN(scores[i]) || Double.isNaN(fwd[i])) {
                continue;
            }
            double s = scores[i] - ms;
            double f = fwd[i] - mf;
            if (s == 0 || f == 0) {
                continue;
            }
            pairs++;
            if ((s > 0) == (f > 0)) {
                hits++;
            }
        }
        return new double[]{hits, pairs};
    }

    /**
     * Mean forward return per score quantile: {@code meanReturns()[0]} is the
     * lowest-scored names' average forward return, the last entry the
     * highest-scored. A real factor shows monotone quantile returns; a factor
     * whose spread lives in one extreme quantile is a tail bet.
     *
     * @param spreadSeries per-date top-minus-bottom return
     * @param spreadTStat  Newey-West t-stat of the mean spread (automatic lag;
     *                     the date grid does not overlap)
     */
    public record QuantileReport(String factorName, double[] meanReturns, int[] counts,
                                 int quantiles, int dates, double[] spreadSeries,
                                 double spreadTStat) {

        /** Top-quantile minus bottom-quantile mean forward return. */
        public double spread() {
            return meanReturns[meanReturns.length - 1] - meanReturns[0];
        }
    }

    /**
     * Buckets each evaluation date's cross-section into {@code quantiles}
     * score-ranked groups and averages forward returns per group. Dates step
     * by {@code horizon} from {@code startIndex} (non-overlapping); a NaN
     * score or forward return drops that pair; a date with fewer complete
     * pairs than {@code quantiles} contributes to no bucket. Bucketing is by
     * ascending score rank ({@code rank * quantiles / n}); ties are split by
     * input order at a boundary, so membership is not fully tie-invariant.
     *
     * @param quantiles number of buckets, at least 2 (5 = quintiles)
     */
    public static QuantileReport quantileReturns(AlphaContext ctx, AlphaFactor factor,
                                                 int startIndex, int horizon, int quantiles) {
        if (horizon <= 0 || startIndex < 0) {
            throw new IllegalArgumentException("need horizon > 0 and startIndex >= 0");
        }
        if (quantiles < 2) {
            throw new IllegalArgumentException("need quantiles >= 2, got " + quantiles);
        }
        double[] sums = new double[quantiles];
        int[] counts = new int[quantiles];
        double[] spreads = new double[Math.max(0, (ctx.bars() - startIndex) / horizon + 1)];
        int dates = 0;
        for (int t = startIndex; t + horizon < ctx.bars(); t += horizon) {
            double[] scores = factor.scores(ctx, t);
            double[] fwd = forwardReturns(ctx, t, horizon);
            int n = 0;
            for (int i = 0; i < scores.length; i++) {
                if (!Double.isNaN(scores[i]) && !Double.isNaN(fwd[i])) {
                    n++;
                }
            }
            if (n < quantiles) {
                continue;
            }
            double[] s = new double[n];
            double[] f = new double[n];
            int k = 0;
            for (int i = 0; i < scores.length; i++) {
                if (!Double.isNaN(scores[i]) && !Double.isNaN(fwd[i])) {
                    s[k] = scores[i];
                    f[k] = fwd[i];
                    k++;
                }
            }
            Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) {
                order[i] = i;
            }
            java.util.Arrays.sort(order, (a, b) -> Double.compare(s[a], s[b]));
            double[] dateSum = new double[quantiles];
            int[] dateCount = new int[quantiles];
            for (int rank = 0; rank < n; rank++) {
                int bucket = (int) ((long) rank * quantiles / n);
                sums[bucket] += f[order[rank]];
                counts[bucket]++;
                dateSum[bucket] += f[order[rank]];
                dateCount[bucket]++;
            }
            spreads[dates++] = dateSum[quantiles - 1] / dateCount[quantiles - 1]
                    - dateSum[0] / dateCount[0];
        }
        if (dates == 0) {
            throw new IllegalArgumentException(
                    "no date had >= " + quantiles + " scored names; shrink quantiles or the NaNs");
        }
        double[] means = new double[quantiles];
        for (int q = 0; q < quantiles; q++) {
            means[q] = sums[q] / counts[q];
        }
        double[] spreadSeries = java.util.Arrays.copyOf(spreads, dates);
        double spreadT = dates < 2 ? Double.NaN
                : NeweyWest.tStat(spreadSeries, NeweyWest.automaticLag(spreadSeries));
        return new QuantileReport(factor.name(), means, counts, quantiles, dates,
                spreadSeries, spreadT);
    }

    /**
     * Mean cross-sectional rank correlation between two factors' scores —
     * how much of factor B is already inside factor A. Above ~0.7 the
     * "new" factor adds little beyond the old one.
     */
    public static double factorExposure(AlphaContext ctx, AlphaFactor a, AlphaFactor b,
                                        int startIndex, int step) {
        double sum = 0;
        int n = 0;
        for (int t = startIndex; t < ctx.bars(); t += step) {
            double rho = spearman(a.scores(ctx, t), b.scores(ctx, t));
            if (!Double.isNaN(rho)) {
                sum += rho;
                n++;
            }
        }
        if (n == 0) {
            throw new IllegalArgumentException("no overlapping scored dates");
        }
        return sum / n;
    }

    /** Forward simple returns over {@code (t, t+h]}, aligned with symbols. */
    static double[] forwardReturns(AlphaContext ctx, int t, int horizon) {
        double[] fwd = new double[ctx.symbolCount()];
        for (int i = 0; i < fwd.length; i++) {
            fwd[i] = ctx.returnOver(i, t, t + horizon);
        }
        return fwd;
    }

    /**
     * Spearman rank correlation over pairwise-complete entries: rank both
     * sides (midranks for ties), then Pearson on the ranks. NaN when fewer
     * than 3 complete pairs — a correlation of 2 points is noise.
     */
    static double spearman(double[] x, double[] y) {
        // Collect pairwise-complete entries.
        int n = 0;
        for (int i = 0; i < x.length; i++) {
            if (!Double.isNaN(x[i]) && !Double.isNaN(y[i])) {
                n++;
            }
        }
        if (n < 3) {
            return Double.NaN;
        }
        double[] xs = new double[n];
        double[] ys = new double[n];
        int k = 0;
        for (int i = 0; i < x.length; i++) {
            if (!Double.isNaN(x[i]) && !Double.isNaN(y[i])) {
                xs[k] = x[i];
                ys[k] = y[i];
                k++;
            }
        }
        return MathUtils.correlation(ranks(xs), ranks(ys));
    }

    /**
     * Midrank transform (average rank for ties), 1-based; the values only
     * feed Pearson. Primitive throughout: sort a copy, then each value's
     * midrank is the average of its first/last positions in the sorted
     * array via binary search. No boxed index sort, no comparator dispatch.
     */
    private static double[] ranks(double[] v) {
        int n = v.length;
        double[] sorted = v.clone();
        java.util.Arrays.sort(sorted);
        double[] rank = new double[n];
        for (int i = 0; i < n; i++) {
            int lo = lowerBound(sorted, v[i]);
            int hi = upperBound(sorted, v[i]);
            rank[i] = (lo + hi - 1) / 2.0 + 1; // midrank across the tie run
        }
        return rank;
    }

    /** First index with {@code sorted[idx] >= key}. */
    private static int lowerBound(double[] sorted, double key) {
        int lo = 0;
        int hi = sorted.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted[mid] < key) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** First index with {@code sorted[idx] > key}. */
    private static int upperBound(double[] sorted, double key) {
        int lo = 0;
        int hi = sorted.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted[mid] <= key) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }
}
