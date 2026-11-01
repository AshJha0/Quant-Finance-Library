package com.quantfinlib.alpha;

import com.quantfinlib.util.MathUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Validation for alpha factors, run before any capital-weighted conclusion:
 *
 * <ul>
 *   <li><b>Walk-forward</b>: pick the best candidate on a training window by
 *       in-sample IC, measure it on the next unseen window, roll forward.
 *       Folds are weighted by their number of IC observations, the
 *       stitched out-of-sample IC series gets a Newey-West t-stat, and the
 *       in-sample winner is haircut by the expected maximum of N null
 *       candidates.</li>
 *   <li><b>Blocked cross-validation</b>: the IC recomputed on k contiguous
 *       time blocks; a factor that only works in one block is a regime
 *       story.</li>
 *   <li><b>Permutation nulls</b> ({@link NullModel}): the cross-sectional
 *       label permutation tests predictive power; the circular block shift
 *       keeps every autocorrelation; the date permutation is a diagnostic
 *       for whether the ranking varies over time.</li>
 *   <li><b>Parameter sensitivity</b>: mean IC across an ordered sweep and
 *       the drop from the best value to its worse neighbour.</li>
 * </ul>
 */
public final class AlphaValidation {

    private static final double EULER_GAMMA = 0.5772156649015329;

    private AlphaValidation() {
    }

    // ------------------------------------------------------------------
    // Walk-forward
    // ------------------------------------------------------------------

    /**
     * One walk-forward fold.
     *
     * @param inSampleObservations  training-window IC dates of the chosen candidate
     * @param outOfSampleObservations test-window IC dates (NaN IC when 0)
     * @param selectionBias         expected maximum in-sample mean IC of the
     *                              fold's candidates under the null (0 for one candidate)
     */
    public record Fold(int trainStart, int testStart, int testEnd,
                       String chosenFactor, double inSampleIc, double outOfSampleIc,
                       int inSampleObservations, int outOfSampleObservations,
                       double selectionBias) {
    }

    /**
     * All folds plus the aggregate in-sample vs out-of-sample comparison.
     *
     * @param meanInSampleIc    observation-weighted mean training IC of the winners
     * @param meanOutOfSampleIc observation-weighted mean test IC (equal to
     *                          the mean of {@code outOfSampleIcSeries})
     * @param outOfSampleIcSeries every test-window IC of the chosen candidates, in order
     * @param outOfSampleTStat  Newey-West t-stat of that series (automatic lag)
     * @param meanSelectionBias observation-weighted mean of the folds' selection bias
     */
    public record WalkForwardResult(List<Fold> folds, double meanInSampleIc,
                                    double meanOutOfSampleIc, double[] outOfSampleIcSeries,
                                    double outOfSampleTStat, double meanSelectionBias) {

        /**
         * OOS/IS efficiency, NaN when the in-sample IC is not positive.
         * Below about 0.5 the selection is mostly fitting noise.
         */
        public double efficiency() {
            return meanInSampleIc > 0 ? meanOutOfSampleIc / meanInSampleIc : Double.NaN;
        }

        /** In-sample IC net of the expected best-of-N luck. */
        public double deflatedInSampleIc() {
            return meanInSampleIc - meanSelectionBias;
        }
    }

    /**
     * Rolls a train/test split across the sample: each fold picks the
     * candidate with the best training-window mean IC and scores it on the
     * next {@code testBars} bars.
     *
     * <p>Evaluation dates lie on one grid ({@code startIndex}, stepping by
     * the horizon) shared by every fold, and the IC matrix is computed once.
     * A date contributes to a window only when its whole forward window fits
     * inside it, so training ICs never see test-window returns.</p>
     *
     * <p>Selection bias: with N candidates whose training mean ICs are
     * independent draws from {@code N(0, se²)} under the null, the expected
     * maximum is {@code se·((1−γ)Φ⁻¹(1−1/N) + γΦ⁻¹(1−1/(Ne)))} (Bailey and
     * López de Prado 2014, J. Portfolio Management 40(5), eq. 6), with
     * {@code se} the standard error of the winner's training mean IC.
     * Neighbouring parameter values are correlated, so the effective N is
     * smaller and this haircut is an upper bound.</p>
     *
     * @param candidates the competing variants (for example one factor
     *                   across a lookback grid)
     */
    public static WalkForwardResult walkForward(AlphaContext ctx, List<AlphaFactor> candidates,
                                                int horizon, int startIndex,
                                                int trainBars, int testBars) {
        if (candidates.isEmpty() || trainBars <= horizon || testBars <= horizon) {
            throw new IllegalArgumentException(
                    "need candidates and train/test windows longer than the horizon");
        }
        int[] dates = grid(ctx, startIndex, horizon);
        double[][] ic = icMatrix(ctx, candidates, dates, horizon);

        List<Fold> folds = new ArrayList<>();
        List<Double> oosSeries = new ArrayList<>();
        double isWeighted = 0;
        double biasWeighted = 0;
        int isWeight = 0;
        for (int trainStart = startIndex;
             trainStart + trainBars + testBars <= ctx.bars();
             trainStart += testBars) {
            int testStart = trainStart + trainBars;
            int testEnd = testStart + testBars;
            int best = -1;
            double bestIc = Double.NEGATIVE_INFINITY;
            for (int c = 0; c < candidates.size(); c++) {
                double mean = windowStats(ic[c], dates, trainStart, testStart, horizon)[0];
                if (!Double.isNaN(mean) && mean > bestIc) {
                    bestIc = mean;
                    best = c;
                }
            }
            if (best < 0) {
                throw new IllegalArgumentException(
                        "no candidate produced a training IC in fold starting at " + trainStart
                                + "; factor warm-up likely exceeds the training window");
            }
            double[] is = windowStats(ic[best], dates, trainStart, testStart, horizon);
            int isN = (int) is[2];
            double se = isN > 1 ? is[1] / Math.sqrt(isN) : 0;
            double bias = expectedMaxOfNulls(candidates.size(), se);
            double oosSum = 0;
            int oosN = 0;
            for (int d = 0; d < dates.length; d++) {
                int t = dates[d];
                if (t >= testStart && t + horizon < testEnd && !Double.isNaN(ic[best][d])) {
                    oosSeries.add(ic[best][d]);
                    oosSum += ic[best][d];
                    oosN++;
                }
            }
            folds.add(new Fold(trainStart, testStart, testEnd, candidates.get(best).name(),
                    bestIc, oosN == 0 ? Double.NaN : oosSum / oosN, isN, oosN, bias));
            isWeighted += bestIc * isN;
            biasWeighted += bias * isN;
            isWeight += isN;
        }
        if (folds.isEmpty()) {
            throw new IllegalArgumentException("sample too short for one train+test fold");
        }
        double[] oos = new double[oosSeries.size()];
        for (int i = 0; i < oos.length; i++) {
            oos[i] = oosSeries.get(i);
        }
        double oosMean = oos.length == 0 ? Double.NaN : MathUtils.mean(oos);
        double oosT = oos.length < 3 ? Double.NaN
                : NeweyWest.tStat(oos, NeweyWest.automaticLag(oos));
        return new WalkForwardResult(List.copyOf(folds), isWeighted / isWeight, oosMean, oos,
                oosT, biasWeighted / isWeight);
    }

    /** Expected maximum of {@code n} iid N(0, se²) draws; 0 for n = 1. */
    static double expectedMaxOfNulls(int n, double se) {
        if (n < 2 || se <= 0) {
            return 0;
        }
        return se * ((1 - EULER_GAMMA) * MathUtils.normInv(1 - 1.0 / n)
                + EULER_GAMMA * MathUtils.normInv(1 - 1.0 / (n * Math.E)));
    }

    /** Evaluation dates: startIndex, stepping by horizon, forward window in-sample. */
    private static int[] grid(AlphaContext ctx, int startIndex, int horizon) {
        int count = 0;
        for (int t = startIndex; t + horizon < ctx.bars(); t += horizon) {
            count++;
        }
        int[] dates = new int[count];
        int i = 0;
        for (int t = startIndex; t + horizon < ctx.bars(); t += horizon) {
            dates[i++] = t;
        }
        return dates;
    }

    /** Per-candidate IC at every grid date, computed once. */
    private static double[][] icMatrix(AlphaContext ctx, List<AlphaFactor> candidates,
                                       int[] dates, int horizon) {
        double[][] ic = new double[candidates.size()][dates.length];
        for (int d = 0; d < dates.length; d++) {
            double[] fwd = SignalEvaluator.forwardReturns(ctx, dates[d], horizon);
            for (int c = 0; c < candidates.size(); c++) {
                ic[c][d] = SignalEvaluator.spearman(candidates.get(c).scores(ctx, dates[d]), fwd);
            }
        }
        return ic;
    }

    /**
     * {mean, sample std, count} of the ICs at grid dates whose whole forward
     * window fits in [from, to); mean NaN when empty.
     */
    private static double[] windowStats(double[] ic, int[] dates, int from, int to, int horizon) {
        double sum = 0;
        double sq = 0;
        int n = 0;
        for (int d = 0; d < dates.length; d++) {
            int t = dates[d];
            if (t >= from && t + horizon < to && !Double.isNaN(ic[d])) {
                sum += ic[d];
                sq += ic[d] * ic[d];
                n++;
            }
        }
        if (n == 0) {
            return new double[]{Double.NaN, Double.NaN, 0};
        }
        double mean = sum / n;
        double var = n > 1 ? Math.max(0, (sq - n * mean * mean) / (n - 1)) : 0;
        return new double[]{mean, Math.sqrt(var), n};
    }

    // ------------------------------------------------------------------
    // Blocked cross-validation
    // ------------------------------------------------------------------

    /** Per-block ICs with their dispersion: consistency across regimes. */
    public record CrossValidationResult(double[] blockIcs, double meanIc, double icStd) {

        /** Fraction of blocks where the IC kept the overall sign. */
        public double signConsistency() {
            if (blockIcs.length == 0) {
                return 0;
            }
            double sign = Math.signum(meanIc);
            int agree = 0;
            for (double ic : blockIcs) {
                if (Math.signum(ic) == sign) {
                    agree++;
                }
            }
            return (double) agree / blockIcs.length;
        }
    }

    /**
     * Splits the evaluation range into {@code k} contiguous blocks and
     * recomputes the mean IC inside each. Stateless factors have nothing to
     * fit, so this is a consistency check rather than a fit/test split.
     */
    public static CrossValidationResult crossValidate(AlphaContext ctx, AlphaFactor factor,
                                                      int horizon, int startIndex, int k) {
        int span = ctx.bars() - startIndex;
        if (k < 2 || span / k <= horizon) {
            throw new IllegalArgumentException("blocks must be longer than the horizon");
        }
        double[] ics = new double[k];
        int blockLen = span / k;
        for (int b = 0; b < k; b++) {
            int from = startIndex + b * blockLen;
            int to = b == k - 1 ? ctx.bars() : from + blockLen;
            ics[b] = meanIc(ctx, factor, from, to, horizon);
        }
        return new CrossValidationResult(ics, MathUtils.mean(ics), MathUtils.stdDev(ics));
    }

    // ------------------------------------------------------------------
    // Permutation nulls
    // ------------------------------------------------------------------

    /** Which link the null distribution destroys. */
    public enum NullModel {
        /**
         * Within each date, permute the symbol labels of the forward-return
         * vector. Destroys the cross-sectional score/return link while
         * keeping both marginals and every time-series dependence. This is
         * the test of predictive power, and the default.
         */
        CROSS_SECTIONAL,
        /**
         * Pair the scores of date {@code d} with the returns of date
         * {@code (d + k) mod D} for every shift {@code k = 1..D−1} (an exact
         * enumeration). Keeps the persistence of both sides. A static
         * ranking against a persistent drift is invariant under the shift,
         * so p is near 1 for it: this asks whether the timing of the signal
         * carries information beyond a fixed ranking.
         */
        CIRCULAR_SHIFT,
        /**
         * Random permutation of score dates against return dates. A
         * diagnostic for time variation of the ranking, not a test of
         * predictive power: slow factors (value, quality, low volatility,
         * 12-1 momentum) get p near 1 under it whatever their skill.
         */
        DATE_PERMUTATION
    }

    /**
     * Observed mean IC against its null distribution.
     *
     * @param trials number of null draws actually used (for
     *               {@link NullModel#CIRCULAR_SHIFT}, {@code D − 1})
     */
    public record RobustnessResult(double observedMeanIc, double pValue,
                                   double nullMean, double nullStd, int trials,
                                   NullModel nullModel) {
    }

    /** {@link #monteCarloRobustness(AlphaContext, AlphaFactor, int, int, int, long, NullModel)} with the cross-sectional null. */
    public static RobustnessResult monteCarloRobustness(AlphaContext ctx, AlphaFactor factor,
                                                        int horizon, int startIndex,
                                                        int trials, long seed) {
        return monteCarloRobustness(ctx, factor, horizon, startIndex, trials, seed,
                NullModel.CROSS_SECTIONAL);
    }

    /**
     * Permutation test of the mean rank IC on the non-overlapping grid
     * ({@code startIndex}, stepping by {@code horizon}). The p-value is the
     * add-one smoothed fraction of null draws whose |mean IC| reaches the
     * observed |mean IC| (two-sided; Phipson and Smyth 2010, Stat. Appl.
     * Genet. Mol. Biol. 9(1)).
     *
     * <p>The cross-sectional null treats symbols as exchangeable within a
     * date; strongly correlated names (one sector driving a small panel)
     * make the null slightly too narrow.</p>
     *
     * @param trials random draws, at least 10 (ignored by the exact
     *               circular shift)
     */
    public static RobustnessResult monteCarloRobustness(AlphaContext ctx, AlphaFactor factor,
                                                        int horizon, int startIndex,
                                                        int trials, long seed,
                                                        NullModel model) {
        if (trials < 10) {
            throw new IllegalArgumentException("need at least 10 trials");
        }
        List<double[]> scores = new ArrayList<>();
        List<double[]> forwards = new ArrayList<>();
        for (int t = startIndex; t + horizon < ctx.bars(); t += horizon) {
            scores.add(factor.scores(ctx, t));
            forwards.add(SignalEvaluator.forwardReturns(ctx, t, horizon));
        }
        int dates = scores.size();
        if (dates < 3) {
            throw new IllegalArgumentException("too few evaluation dates for a permutation test");
        }
        double observed = meanIcOf(scores, forwards, identity(dates));
        SplittableRandom rng = new SplittableRandom(seed);
        int draws = model == NullModel.CIRCULAR_SHIFT ? dates - 1 : trials;
        double nullSum = 0;
        double nullSq = 0;
        int asExtreme = 0;
        int used = 0;
        int[] perm = identity(dates);
        for (int trial = 0; trial < draws; trial++) {
            double icNull = switch (model) {
                case DATE_PERMUTATION -> {
                    shuffle(perm, rng);
                    yield meanIcOf(scores, forwards, perm);
                }
                case CIRCULAR_SHIFT -> {
                    for (int d = 0; d < dates; d++) {
                        perm[d] = (d + trial + 1) % dates;
                    }
                    yield meanIcOf(scores, forwards, perm);
                }
                case CROSS_SECTIONAL -> crossSectionalNull(scores, forwards, rng);
            };
            if (Double.isNaN(icNull)) {
                continue;
            }
            used++;
            nullSum += icNull;
            nullSq += icNull * icNull;
            if (Math.abs(icNull) >= Math.abs(observed)) {
                asExtreme++;
            }
        }
        if (used == 0) {
            throw new IllegalArgumentException("no null draw produced an IC");
        }
        double nullMean = nullSum / used;
        double nullVar = nullSq / used - nullMean * nullMean;
        double p = (asExtreme + 1.0) / (used + 1.0);
        return new RobustnessResult(observed, p, nullMean, Math.sqrt(Math.max(0, nullVar)),
                used, model);
    }

    /** Mean IC with each date's forward returns permuted across symbols. */
    private static double crossSectionalNull(List<double[]> scores, List<double[]> forwards,
                                             SplittableRandom rng) {
        double sum = 0;
        int n = 0;
        for (int d = 0; d < scores.size(); d++) {
            double[] s = scores.get(d);
            double[] f = forwards.get(d);
            // Permute only among names alive on both sides, so the number of
            // complete pairs (and the IC's sampling variance) is unchanged.
            int k = 0;
            int[] idx = new int[s.length];
            for (int i = 0; i < s.length; i++) {
                if (!Double.isNaN(s[i]) && !Double.isNaN(f[i])) {
                    idx[k++] = i;
                }
            }
            if (k < 3) {
                continue;
            }
            double[] xs = new double[k];
            double[] ys = new double[k];
            for (int j = 0; j < k; j++) {
                xs[j] = s[idx[j]];
                ys[j] = f[idx[j]];
            }
            for (int j = k - 1; j > 0; j--) {
                int r = rng.nextInt(j + 1);
                double tmp = ys[j];
                ys[j] = ys[r];
                ys[r] = tmp;
            }
            double ic = SignalEvaluator.spearman(xs, ys);
            if (!Double.isNaN(ic)) {
                sum += ic;
                n++;
            }
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    // ------------------------------------------------------------------
    // Parameter sensitivity
    // ------------------------------------------------------------------

    /**
     * IC across the sweep.
     *
     * @param worstNeighborDrop  best mean IC minus the lower of its immediate
     *                           neighbours' mean ICs (0 when the best is
     *                           flanked by equal values)
     * @param largestAdjacentJump largest |IC difference| between any two
     *                           adjacent candidates
     */
    public record SensitivityResult(String[] names, double[] meanIcs, double worstNeighborDrop,
                                    double largestAdjacentJump) {

        /** The candidate with the best mean IC. */
        public String best() {
            return names[bestIndex(meanIcs)];
        }
    }

    /**
     * Evaluates each candidate of an ordered sweep (neighbours in the list
     * are neighbours in parameter space) and reports how far the IC falls
     * when the chosen parameter moves one step. Small drop = plateau; large
     * drop = the chosen value is a spike.
     */
    public static SensitivityResult parameterSensitivity(AlphaContext ctx,
                                                         List<AlphaFactor> sweep,
                                                         int horizon, int startIndex) {
        if (sweep.size() < 2) {
            throw new IllegalArgumentException("a sweep needs at least 2 candidates");
        }
        int[] dates = grid(ctx, startIndex, horizon);
        double[][] icByCandidate = icMatrix(ctx, sweep, dates, horizon);
        String[] names = new String[sweep.size()];
        double[] ics = new double[sweep.size()];
        for (int i = 0; i < sweep.size(); i++) {
            names[i] = sweep.get(i).name();
            ics[i] = windowStats(icByCandidate[i], dates, startIndex, ctx.bars(), horizon)[0];
        }
        int b = bestIndex(ics);
        double worstNeighbour = Double.POSITIVE_INFINITY;
        if (b > 0) {
            worstNeighbour = Math.min(worstNeighbour, ics[b - 1]);
        }
        if (b < ics.length - 1) {
            worstNeighbour = Math.min(worstNeighbour, ics[b + 1]);
        }
        double jump = 0;
        for (int i = 1; i < ics.length; i++) {
            jump = Math.max(jump, Math.abs(ics[i] - ics[i - 1]));
        }
        return new SensitivityResult(names, ics, ics[b] - worstNeighbour, jump);
    }

    private static int bestIndex(double[] ics) {
        int best = 0;
        for (int i = 1; i < ics.length; i++) {
            if (ics[i] > ics[best] || Double.isNaN(ics[best])) {
                best = i;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // Shared IC arithmetic
    // ------------------------------------------------------------------

    /**
     * Mean rank IC over {@code [from, toExclusive)}, stepping by the horizon.
     * The whole forward window {@code (t, t+horizon]} must fit inside the
     * range, so a training window never reads test-window returns.
     */
    static double meanIc(AlphaContext ctx, AlphaFactor factor, int from, int toExclusive,
                         int horizon) {
        double sum = 0;
        int n = 0;
        int end = Math.min(toExclusive, ctx.bars());
        for (int t = from; t + horizon < end; t += horizon) {
            double ic = SignalEvaluator.spearman(factor.scores(ctx, t),
                    SignalEvaluator.forwardReturns(ctx, t, horizon));
            if (!Double.isNaN(ic)) {
                sum += ic;
                n++;
            }
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    private static double meanIcOf(List<double[]> scores, List<double[]> forwards, int[] pairing) {
        double sum = 0;
        int n = 0;
        for (int i = 0; i < pairing.length; i++) {
            double ic = SignalEvaluator.spearman(scores.get(i), forwards.get(pairing[i]));
            if (!Double.isNaN(ic)) {
                sum += ic;
                n++;
            }
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    private static int[] identity(int n) {
        int[] a = new int[n];
        for (int i = 0; i < n; i++) {
            a[i] = i;
        }
        return a;
    }

    /** Fisher-Yates in place. */
    private static void shuffle(int[] a, SplittableRandom rng) {
        for (int i = a.length - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int tmp = a[i];
            a[i] = a[j];
            a[j] = tmp;
        }
    }
}
