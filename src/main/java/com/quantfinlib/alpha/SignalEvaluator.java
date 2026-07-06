package com.quantfinlib.alpha;

import com.quantfinlib.util.MathUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Signal evaluation — the metrics that decide whether a factor is worth
 * constructing a portfolio from, computed <em>before</em> any backtest so
 * weak signals die cheaply:
 *
 * <ul>
 *   <li><b>IC</b> (information coefficient) — Spearman rank correlation
 *       between scores at {@code t} and forward returns over
 *       {@code (t, t+horizon]}, per evaluation date. Rank (not Pearson)
 *       because factor scores have arbitrary units and fat tails; rank IC
 *       is invariant to any monotone transform of the signal.</li>
 *   <li><b>IR</b> — {@code mean(IC) / std(IC)}: signal strength per unit of
 *       signal inconsistency, the standard Grinold-Kahn quality number
 *       (0.05 mean IC with steady sign beats 0.10 that flips).</li>
 *   <li><b>t-stat</b> — {@code mean(IC) / (std(IC)/√n)}: is the mean IC
 *       distinguishable from zero given how many dates we observed.</li>
 *   <li><b>Hit rate</b> — fraction of (symbol, date) pairs where the score
 *       sign called the forward-return sign.</li>
 *   <li><b>Turnover</b> — half the L1 change in normalized weights between
 *       consecutive evaluation dates: how much trading the signal demands,
 *       the denominator of "does the alpha survive costs".</li>
 *   <li><b>Factor exposure</b> — mean cross-sectional rank correlation
 *       against another factor: a "new" signal that is 0.9 rank-correlated
 *       with momentum is momentum.</li>
 * </ul>
 *
 * <p>Evaluation dates step by {@code horizon} so forward-return windows
 * don't overlap — overlapping windows inflate the t-stat through serial
 * correlation, the classic way factor research fools itself.</p>
 */
public final class SignalEvaluator {

    /** The evaluation scorecard; {@link #format} renders it for humans. */
    public record Report(String factorName, double meanIc, double icStd, double ir,
                         double tStat, double hitRate, double meanTurnover,
                         int observations, double[] icSeries) {

        /** One-line human summary in fixed order for report diffs. */
        public String format() {
            return String.format(Locale.ROOT,
                    "%s: IC=%.4f (t=%.2f, n=%d) IR=%.2f hit=%.1f%% turnover=%.1f%%",
                    factorName, meanIc, tStat, observations, ir,
                    hitRate * 100, meanTurnover * 100);
        }
    }

    private SignalEvaluator() {
    }

    /**
     * Evaluates a factor over {@code [startIndex, ctx.bars() − horizon)},
     * stepping by {@code horizon} (non-overlapping forward windows).
     *
     * @param horizon forward-return horizon in bars (also the step)
     */
    public static Report evaluate(AlphaContext ctx, AlphaFactor factor,
                                  int startIndex, int horizon) {
        if (horizon <= 0 || startIndex < 0) {
            throw new IllegalArgumentException("need horizon > 0 and startIndex >= 0");
        }
        List<Double> ics = new ArrayList<>();
        double hits = 0;
        double hitPairs = 0;
        double turnoverSum = 0;
        int turnoverCount = 0;
        double[] prevWeights = null;

        for (int t = startIndex; t + horizon < ctx.bars(); t += horizon) {
            double[] scores = factor.scores(ctx, t);
            double[] fwd = forwardReturns(ctx, t, horizon);

            // Pairwise-complete: a NaN on either side drops the pair.
            double ic = spearman(scores, fwd);
            if (Double.isNaN(ic)) {
                // Warm-up / unscored date: it is in NO metric's denominator.
                // Counting its all-zero weight vector as a zero-turnover
                // observation would understate turnover against the same
                // dates the IC series excludes.
                continue;
            }
            ics.add(ic);
            for (int i = 0; i < scores.length; i++) {
                if (!Double.isNaN(scores[i]) && scores[i] != 0 && !Double.isNaN(fwd[i])) {
                    hitPairs++;
                    if (Math.signum(scores[i]) == Math.signum(fwd[i])) {
                        hits++;
                    }
                }
            }
            // Turnover on the normalized (gross = 1) weights the scores
            // imply, between consecutive SCORED dates — one denominator
            // shared with the IC series.
            double[] w = PortfolioConstruction.zScoreWeights(scores, 1.0);
            if (prevWeights != null) {
                double l1 = 0;
                for (int i = 0; i < w.length; i++) {
                    l1 += Math.abs(w[i] - prevWeights[i]);
                }
                turnoverSum += l1 / 2; // buys and sells each counted once
                turnoverCount++;
            }
            prevWeights = w;
        }

        int n = ics.size();
        if (n < 2) {
            throw new IllegalArgumentException(
                    "fewer than 2 IC observations — extend the sample or shrink the horizon");
        }
        double[] icArr = new double[n];
        for (int i = 0; i < n; i++) {
            icArr[i] = ics.get(i);
        }
        double mean = MathUtils.mean(icArr);
        double std = MathUtils.stdDev(icArr);
        double ir = std == 0 ? 0 : mean / std;
        return new Report(factor.name(), mean, std, ir,
                std == 0 ? 0 : mean / (std / Math.sqrt(n)),
                hitPairs == 0 ? 0 : hits / hitPairs,
                turnoverCount == 0 ? 0 : turnoverSum / turnoverCount,
                n, icArr);
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

    /** Midrank transform (average rank for ties), 1-based — values only feed Pearson. */
    private static double[] ranks(double[] v) {
        int n = v.length;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> Double.compare(v[a], v[b]));
        double[] rank = new double[n];
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && v[order[j + 1]] == v[order[i]]) {
                j++;
            }
            double mid = (i + j) / 2.0 + 1; // average rank across the tie run
            for (int t = i; t <= j; t++) {
                rank[order[t]] = mid;
            }
            i = j + 1;
        }
        return rank;
    }
}
