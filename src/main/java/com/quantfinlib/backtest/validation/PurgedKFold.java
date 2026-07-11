package com.quantfinlib.backtest.validation;

import java.util.ArrayList;
import java.util.List;

/**
 * PURGED K-fold cross-validation splits with an EMBARGO — the fix for the
 * quiet leak that ordinary K-fold has on financial data (Lopez de Prado,
 * <i>Advances in Financial Machine Learning</i>, ch. 7).
 *
 * <p>The leak: a sample's LABEL is usually computed from bars that come
 * after it (e.g. "the 5-bar forward return"). Ordinary K-fold happily puts
 * bar 99 in the training set and bar 100 in the test set — but bar 99's
 * label was computed from bars 100–104, so the model has already seen the
 * test answer. The backtest looks skillful; the skill is leakage.</p>
 *
 * <p>Two defenses, both index arithmetic:</p>
 * <ul>
 *   <li><b>Purging</b> removes every training sample whose label window
 *       {@code [i, i + labelHorizon]} overlaps any test label window. For a
 *       contiguous test fold {@code [t0, t1)} that means dropping training
 *       indices in {@code [t0 - labelHorizon, t0)} (labels reach INTO the
 *       fold) and {@code [t1, t1 + labelHorizon)} (labels reach OUT of
 *       it).</li>
 *   <li><b>Embargo</b> drops a further {@code embargo} samples after the
 *       purge zone that follows the test fold. Serial correlation means
 *       features just after the test window still echo test-period
 *       information even when the label windows don't overlap; the embargo
 *       is the buffer for that echo. A common choice is ~1% of n.</li>
 * </ul>
 *
 * <p>So the training set for test fold {@code [t0, t1)} is exactly
 * {@code [0, t0 - labelHorizon)} &cup;
 * {@code [t1 + labelHorizon + embargo, n)} — hand-checkable, and the tests
 * do. Every fold's training set must be non-empty or the split refuses:
 * silently training on nothing is how "great" fold scores happen.</p>
 *
 * <p>Static, deterministic, research lane. Pair with
 * {@link OverfitProbability} (is the SELECTION process overfit?) and
 * {@link WalkForwardAnalyzer} (the strictly-forward-in-time variant).</p>
 */
public final class PurgedKFold {

    /**
     * One fold: test on {@code [testFrom, testTo)}, train on
     * {@code trainIndices} (ascending, purged and embargoed).
     */
    public record Split(int fold, int testFrom, int testTo, int[] trainIndices) {
    }

    private PurgedKFold() {
    }

    /**
     * @param n            number of samples (bars/observations), &ge; 2·k
     * @param k            number of folds, &ge; 2
     * @param labelHorizon bars each label looks ahead (0 = label known at
     *                     the sample's own bar; 5 = 5-bar forward return)
     * @param embargo      extra bars dropped after the post-test purge zone
     */
    public static List<Split> splits(int n, int k, int labelHorizon, int embargo) {
        if (k < 2) {
            throw new IllegalArgumentException("k must be >= 2, got " + k);
        }
        if (n < 2 * k) {
            throw new IllegalArgumentException("need n >= 2k samples: n=" + n + " k=" + k);
        }
        if (labelHorizon < 0 || embargo < 0) {
            throw new IllegalArgumentException("labelHorizon and embargo must be >= 0, got "
                    + labelHorizon + " and " + embargo);
        }
        List<Split> out = new ArrayList<>(k);
        for (int f = 0; f < k; f++) {
            int t0 = (int) ((long) f * n / k);
            int t1 = (int) ((long) (f + 1) * n / k);
            int headEnd = Math.max(0, t0 - labelHorizon);          // [0, headEnd)
            int tailStart = Math.min(n, t1 + labelHorizon + embargo); // [tailStart, n)
            int size = headEnd + (n - tailStart);
            if (size == 0) {
                throw new IllegalArgumentException("fold " + f + " leaves no training data: "
                        + "n=" + n + " k=" + k + " labelHorizon=" + labelHorizon
                        + " embargo=" + embargo);
            }
            int[] train = new int[size];
            int w = 0;
            for (int i = 0; i < headEnd; i++) {
                train[w++] = i;
            }
            for (int i = tailStart; i < n; i++) {
                train[w++] = i;
            }
            out.add(new Split(f, t0, t1, train));
        }
        return out;
    }
}
