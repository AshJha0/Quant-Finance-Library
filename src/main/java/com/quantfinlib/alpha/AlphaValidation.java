package com.quantfinlib.alpha;

import com.quantfinlib.util.MathUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Validation for alpha factors — the overfitting defense, run before any
 * capital-weighted conclusion is drawn:
 *
 * <ul>
 *   <li><b>Walk-forward</b> — pick the best factor variant on a training
 *       window by in-sample IC, measure it on the following unseen window,
 *       roll forward. The IS→OOS gap is the overfitting, measured.</li>
 *   <li><b>K-fold (blocked) cross-validation</b> — the IC recomputed on k
 *       contiguous time blocks. Time-series data forbids shuffled folds
 *       (they leak adjacent bars across the train/test line), so blocks it
 *       is; a factor that only works in one block is a regime story, not a
 *       signal.</li>
 *   <li><b>Monte Carlo robustness</b> — a permutation test: re-pair score
 *       dates with return dates at random to build the null distribution
 *       of mean IC, and report where the observed value falls. This asks
 *       the right question ("could this IC arise from no relationship?")
 *       without any normality assumption.</li>
 *   <li><b>Parameter sensitivity</b> — mean IC across a parameter sweep,
 *       plus the worst drop between adjacent parameters. A real effect
 *       degrades smoothly as parameters move; a spike at exactly one value
 *       is the signature of a lucky backtest.</li>
 * </ul>
 */
public final class AlphaValidation {

    private AlphaValidation() {
    }

    // ------------------------------------------------------------------
    // Walk-forward
    // ------------------------------------------------------------------

    /** One walk-forward fold: what was chosen, and how it did out of sample. */
    public record Fold(int trainStart, int testStart, int testEnd,
                       String chosenFactor, double inSampleIc, double outOfSampleIc) {
    }

    /** All folds plus the aggregate in-sample vs out-of-sample comparison. */
    public record WalkForwardResult(List<Fold> folds, double meanInSampleIc,
                                    double meanOutOfSampleIc) {

        /** OOS/IS efficiency: below ~0.5 the selection is mostly fitting noise. */
        public double efficiency() {
            return meanInSampleIc == 0 ? 0 : meanOutOfSampleIc / meanInSampleIc;
        }
    }

    /**
     * Rolls a train/test split across the sample: each fold picks the
     * candidate with the best training-window mean IC and scores it on the
     * next {@code testBars} unseen bars.
     *
     * <p>Evaluation dates lie on ONE global grid ({@code startIndex},
     * stepping by the horizon) shared by every fold: consecutive folds'
     * training windows overlap by {@code trainBars − testBars}, so scoring
     * per fold would recompute the same (candidate, date) work up to
     * {@code trainBars/testBars} times — instead the whole IC matrix is
     * computed once (forward returns shared across candidates, too) and
     * folds average slices of it. Window containment still holds: a date
     * contributes to a window only when its ENTIRE forward window fits
     * inside it.</p>
     *
     * @param candidates the factor variants competing (e.g. one factor
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
        double isSum = 0;
        double oosSum = 0;
        for (int trainStart = startIndex;
             trainStart + trainBars + testBars <= ctx.bars();
             trainStart += testBars) {
            int testStart = trainStart + trainBars;
            int testEnd = testStart + testBars;
            // Model selection happens STRICTLY inside the training window:
            // only dates whose forward window fits before testStart count.
            AlphaFactor best = null;
            double bestIc = Double.NEGATIVE_INFINITY;
            for (int c = 0; c < candidates.size(); c++) {
                double mean = windowMean(ic[c], dates, trainStart, testStart, horizon);
                // NaN (factor entirely in warm-up over this window) never wins.
                if (!Double.isNaN(mean) && mean > bestIc) {
                    bestIc = mean;
                    best = candidates.get(c);
                }
            }
            if (best == null) {
                throw new IllegalArgumentException(
                        "no candidate produced a training IC in fold starting at " + trainStart
                                + " — factor warm-up likely exceeds the training window");
            }
            double oos = windowMean(ic[candidates.indexOf(best)], dates,
                    testStart, testEnd, horizon);
            folds.add(new Fold(trainStart, testStart, testEnd, best.name(), bestIc, oos));
            isSum += bestIc;
            oosSum += oos;
        }
        if (folds.isEmpty()) {
            throw new IllegalArgumentException("sample too short for one train+test fold");
        }
        return new WalkForwardResult(List.copyOf(folds),
                isSum / folds.size(), oosSum / folds.size());
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

    /**
     * Per-candidate IC at every grid date, computed ONCE: forward returns
     * are candidate-independent and shared across the whole sweep.
     */
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

    /** Mean IC over grid dates whose whole forward window fits in [from, to). */
    private static double windowMean(double[] ic, int[] dates, int from, int to, int horizon) {
        double sum = 0;
        int n = 0;
        for (int d = 0; d < dates.length; d++) {
            int t = dates[d];
            if (t >= from && t + horizon < to && !Double.isNaN(ic[d])) {
                sum += ic[d];
                n++;
            }
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    // ------------------------------------------------------------------
    // Blocked cross-validation
    // ------------------------------------------------------------------

    /** Per-block ICs with their dispersion — consistency across regimes. */
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
     * recomputes the mean IC inside each. (Stateless factors have nothing
     * to fit, so this is a pure consistency check — the honest reading of
     * "cross-validation" for unfitted signals.)
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
    // Monte Carlo robustness (permutation test)
    // ------------------------------------------------------------------

    /** Observed mean IC against its permutation null distribution. */
    public record RobustnessResult(double observedMeanIc, double pValue,
                                   double nullMean, double nullStd, int trials) {
    }

    /**
     * Permutation test on the score/return pairing: per trial, scores from
     * date {@code tᵢ} are paired with forward returns from a shuffled date
     * {@code tⱼ}, destroying any true predictive link while preserving both
     * marginal distributions. The p-value is the fraction of trials whose
     * |mean IC| reaches the observed |mean IC| (two-sided, add-one
     * smoothed so p is never exactly 0).
     *
     * <p><b>Deliberate conservatism</b>: a signal whose scores never change
     * over time (a static ranking) is invariant under date permutation, so
     * it earns p ≈ 1 regardless of its in-sample IC — correctly so, because
     * a time-invariant cross-section against persistent drifts is one
     * effective observation, however many dates it is sampled on. Only
     * signals whose time variation aligns with return variation can earn a
     * small p here.</p>
     */
    public static RobustnessResult monteCarloRobustness(AlphaContext ctx, AlphaFactor factor,
                                                        int horizon, int startIndex,
                                                        int trials, long seed) {
        if (trials < 10) {
            throw new IllegalArgumentException("need at least 10 trials");
        }
        // Precompute per-date scores and forward returns once.
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

        Random rng = new Random(seed);
        double nullSum = 0;
        double nullSq = 0;
        int asExtreme = 0;
        int[] perm = identity(dates);
        for (int trial = 0; trial < trials; trial++) {
            shuffle(perm, rng);
            double icNull = meanIcOf(scores, forwards, perm);
            nullSum += icNull;
            nullSq += icNull * icNull;
            if (Math.abs(icNull) >= Math.abs(observed)) {
                asExtreme++;
            }
        }
        double nullMean = nullSum / trials;
        double nullVar = nullSq / trials - nullMean * nullMean;
        // Add-one smoothing: with T trials the resolution is 1/(T+1).
        double p = (asExtreme + 1.0) / (trials + 1.0);
        return new RobustnessResult(observed, p, nullMean, Math.sqrt(Math.max(0, nullVar)), trials);
    }

    // ------------------------------------------------------------------
    // Parameter sensitivity
    // ------------------------------------------------------------------

    /** IC across the sweep plus the worst adjacent-parameter drop. */
    public record SensitivityResult(String[] names, double[] meanIcs, double worstNeighborDrop) {

        /** The candidate with the best mean IC. */
        public String best() {
            int best = 0;
            for (int i = 1; i < meanIcs.length; i++) {
                if (meanIcs[i] > meanIcs[best]) {
                    best = i;
                }
            }
            return names[best];
        }
    }

    /**
     * Evaluates each candidate (an ORDERED parameter sweep — neighbors in
     * the list must be neighbors in parameter space) and reports the worst
     * IC drop between adjacent candidates. Small drop = plateau = robust;
     * large drop = the chosen parameter is a lucky spike.
     */
    public static SensitivityResult parameterSensitivity(AlphaContext ctx,
                                                         List<AlphaFactor> sweep,
                                                         int horizon, int startIndex) {
        if (sweep.size() < 2) {
            throw new IllegalArgumentException("a sweep needs at least 2 candidates");
        }
        // Shared grid + shared forward returns across the sweep — the same
        // caching walkForward uses: forward returns are candidate-free.
        int[] dates = grid(ctx, startIndex, horizon);
        double[][] icByCandidate = icMatrix(ctx, sweep, dates, horizon);
        String[] names = new String[sweep.size()];
        double[] ics = new double[sweep.size()];
        for (int i = 0; i < sweep.size(); i++) {
            names[i] = sweep.get(i).name();
            ics[i] = windowMean(icByCandidate[i], dates, startIndex, ctx.bars(), horizon);
        }
        double worstDrop = 0;
        for (int i = 1; i < ics.length; i++) {
            worstDrop = Math.max(worstDrop, Math.abs(ics[i] - ics[i - 1]));
        }
        return new SensitivityResult(names, ics, worstDrop);
    }

    // ------------------------------------------------------------------
    // Shared IC arithmetic
    // ------------------------------------------------------------------

    /**
     * Mean rank IC over {@code [from, toExclusive)}, stepping by the horizon.
     * The ENTIRE forward window {@code (t, t+horizon]} must fit inside the
     * range: letting it spill past {@code toExclusive} would leak test-window
     * returns into training-window ICs — the walk-forward selection would
     * peek at exactly the data it claims not to see.
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
    private static void shuffle(int[] a, Random rng) {
        for (int i = a.length - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int tmp = a[i];
            a[i] = a[j];
            a[j] = tmp;
        }
    }
}
