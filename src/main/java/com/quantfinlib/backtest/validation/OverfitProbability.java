package com.quantfinlib.backtest.validation;

import com.quantfinlib.util.MathUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * PROBABILITY OF BACKTEST OVERFITTING via combinatorially symmetric
 * cross-validation — CSCV (Bailey, Borwein, Lopez de Prado &amp; Zhu 2015,
 * "The probability of backtest overfitting").
 *
 * <p>{@link SharpeValidation} asks whether ONE track record is luck. This
 * class asks the prior question: is the SELECTION PROCESS itself broken?
 * When a desk tries N parameter sets and reports the best, the reported
 * Sharpe is a maximum of N draws — and the right diagnostic is: how often
 * does the in-sample winner turn out to be a BELOW-MEDIAN performer out of
 * sample?</p>
 *
 * <p>The construction: take the T&times;N matrix of per-period returns
 * (one column per strategy variant), slice time into S equal blocks, and
 * form every one of the C(S, S/2) ways to pick half the blocks as
 * in-sample (IS) and the complementary half as out-of-sample (OOS) —
 * symmetric by construction, so IS and OOS have identical length and no
 * arrow of time bias. For each combination:</p>
 * <ol>
 *   <li>concatenate the IS blocks and pick the variant with the best IS
 *       objective (ties break to the first column — stated, and the tie
 *       ranks below make that conservative);</li>
 *   <li>rank that winner's OOS objective among all N variants:
 *       {@code rank = 1 + #(strictly worse)}, relative rank
 *       {@code w = rank / (N + 1)} (never exactly 0 or 1);</li>
 *   <li>record the logit {@code lambda = ln(w / (1 - w))} — positive means
 *       the IS winner was above the OOS median, negative below.</li>
 * </ol>
 *
 * <p><b>PBO = the fraction of combinations with {@code lambda <= 0}</b>:
 * the probability that the config you would have picked is an
 * out-of-sample loser. Rules of thumb: PBO &lt; 0.1 — selection is finding
 * something real; PBO &ge; 0.5 — the selection is pure noise-mining and
 * the "best" backtest is meaningless regardless of how good it looks.</p>
 *
 * <p>Trailing periods that don't fill a whole block are dropped (stated:
 * with T = 1007 and S = 8, each block is 125 periods and the last 7 are
 * unused). S is capped at 16 — C(16,8) = 12,870 combinations is already a
 * full re-scoring of every variant 12,870 times; beyond that the cost
 * explodes for no statistical gain. Deterministic (no RNG), research
 * lane.</p>
 */
public final class OverfitProbability {

    /** Hard cap on blocks: C(16,8)=12,870 combinations. */
    private static final int MAX_BLOCKS = 16;

    /**
     * @param pbo          fraction of IS/OOS splits whose in-sample winner
     *                     ranked at or below the out-of-sample median
     * @param combinations number of symmetric splits evaluated, C(S, S/2)
     * @param logits       one lambda per combination (enumeration order)
     */
    public record Result(double pbo, int combinations, double[] logits) {
    }

    private OverfitProbability() {
    }

    /**
     * CSCV with the caller's objective (applied to a concatenated return
     * sub-series; higher is better).
     *
     * @param returns   T&times;N rectangular matrix: {@code returns[t][j]} =
     *                  period-t return of strategy variant j; all finite,
     *                  N &ge; 2
     * @param blocks    S: even, 4 &le; S &le; 16; each block needs &ge; 2
     *                  periods
     * @param objective score for a variant's return series, e.g. mean or
     *                  per-period Sharpe; higher is better, must be finite
     */
    public static Result cscv(double[][] returns, int blocks,
                              ToDoubleFunction<double[]> objective) {
        validate(returns, blocks);
        int nVariants = returns[0].length;
        int blockLen = returns.length / blocks;
        int half = blocks / 2;

        List<int[]> combos = new ArrayList<>();
        enumerate(new int[half], 0, 0, blocks, combos);

        double[] logits = new double[combos.size()];
        boolean[] inSample = new boolean[blocks];
        int below = 0;
        for (int c = 0; c < combos.size(); c++) {
            java.util.Arrays.fill(inSample, false);
            for (int b : combos.get(c)) {
                inSample[b] = true;
            }
            // Score every variant IS and OOS on this split.
            double bestIs = Double.NEGATIVE_INFINITY;
            int winner = -1;
            double[] oosScores = new double[nVariants];
            for (int j = 0; j < nVariants; j++) {
                double is = score(returns, inSample, true, blockLen, half, j, objective);
                oosScores[j] = score(returns, inSample, false, blockLen, half, j, objective);
                if (is > bestIs) {
                    bestIs = is;
                    winner = j;
                }
            }
            int rank = 1;
            for (int j = 0; j < nVariants; j++) {
                if (oosScores[j] < oosScores[winner]) {
                    rank++;
                }
            }
            double w = (double) rank / (nVariants + 1);
            double lambda = Math.log(w / (1 - w));
            logits[c] = lambda;
            if (lambda <= 0) {
                below++;
            }
        }
        return new Result((double) below / combos.size(), combos.size(), logits);
    }

    /**
     * CSCV with the per-period Sharpe objective {@code mean / stdDev}
     * (sample standard deviation; a zero-variance sub-series scores 0 —
     * a flat line has no risk-adjusted evidence either way).
     */
    public static Result cscvSharpe(double[][] returns, int blocks) {
        return cscv(returns, blocks, r -> {
            double sd = MathUtils.stdDev(r);
            return sd > 0 ? MathUtils.mean(r) / sd : 0;
        });
    }

    private static double score(double[][] returns, boolean[] inSample, boolean wantIs,
                                int blockLen, int half, int variant,
                                ToDoubleFunction<double[]> objective) {
        double[] series = new double[half * blockLen];
        int w = 0;
        for (int b = 0; b < inSample.length; b++) {
            if (inSample[b] == wantIs) {
                int from = b * blockLen;
                for (int t = 0; t < blockLen; t++) {
                    series[w++] = returns[from + t][variant];
                }
            }
        }
        double s = objective.applyAsDouble(series);
        // Finite required, not merely non-NaN: an all -Infinity in-sample
        // column would leave the argmax with no winner at all.
        if (!Double.isFinite(s)) {
            throw new IllegalArgumentException(
                    "objective returned non-finite " + s + " for variant " + variant);
        }
        return s;
    }

    /** All C(blocks, half) ascending index combinations, lexicographic. */
    private static void enumerate(int[] pick, int pos, int from, int blocks, List<int[]> out) {
        if (pos == pick.length) {
            out.add(pick.clone());
            return;
        }
        for (int b = from; b <= blocks - (pick.length - pos); b++) {
            pick[pos] = b;
            enumerate(pick, pos + 1, b + 1, blocks, out);
        }
    }

    private static void validate(double[][] returns, int blocks) {
        if (blocks < 4 || blocks % 2 != 0 || blocks > MAX_BLOCKS) {
            throw new IllegalArgumentException(
                    "blocks must be even, in [4, " + MAX_BLOCKS + "], got " + blocks);
        }
        if (returns.length < 2 * blocks) {
            throw new IllegalArgumentException("need >= 2 periods per block: "
                    + returns.length + " periods for " + blocks + " blocks");
        }
        int nVariants = returns[0].length;
        if (nVariants < 2) {
            throw new IllegalArgumentException("need >= 2 strategy variants, got " + nVariants);
        }
        for (int t = 0; t < returns.length; t++) {
            if (returns[t].length != nVariants) {
                throw new IllegalArgumentException("ragged matrix: row " + t + " has "
                        + returns[t].length + " variants, row 0 has " + nVariants);
            }
            for (int j = 0; j < nVariants; j++) {
                if (!Double.isFinite(returns[t][j])) {
                    throw new IllegalArgumentException(
                            "non-finite return at period " + t + ", variant " + j);
                }
            }
        }
    }
}
