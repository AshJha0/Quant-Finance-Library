package com.quantfinlib.backtest;

import com.quantfinlib.backtest.validation.OverfitProbability;
import com.quantfinlib.backtest.validation.PurgedKFold;
import com.quantfinlib.risk.RiskMetrics;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hand-computed pins for the backtest-robustness layer: purged K-fold
 * splits, CSCV probability of backtest overfitting, benchmark-relative
 * analytics and drawdown structure. Every expected value below is
 * derivable on paper from the class javadoc — that is the point.
 */
class ValidationRobustnessTest {

    // ---------------------------------------------------------------- PurgedKFold

    @Test
    void purgedSplitsMatchHandDerivedIndexArithmetic() {
        // n=20, k=4, labelHorizon=2, embargo=1. Fold 1 tests [5,10):
        // head = [0, 5-2) = {0,1,2}; tail = [10+2+1, 20) = {13..19}.
        List<PurgedKFold.Split> splits = PurgedKFold.splits(20, 4, 2, 1);
        assertEquals(4, splits.size());

        PurgedKFold.Split f1 = splits.get(1);
        assertEquals(5, f1.testFrom());
        assertEquals(10, f1.testTo());
        assertArrayEquals(new int[]{0, 1, 2, 13, 14, 15, 16, 17, 18, 19}, f1.trainIndices());

        // Fold 0 has no head at all: train = [0+5+2+1, 20) = {8..19}.
        PurgedKFold.Split f0 = splits.get(0);
        assertArrayEquals(new int[]{8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19},
                f0.trainIndices());

        // Test ranges partition [0, 20) with no gaps.
        int covered = 0;
        for (PurgedKFold.Split s : splits) {
            covered += s.testTo() - s.testFrom();
        }
        assertEquals(20, covered);
    }

    @Test
    void zeroHorizonZeroEmbargoReducesToPlainKFold() {
        // With nothing to purge, train must be the exact complement of test.
        for (PurgedKFold.Split s : PurgedKFold.splits(12, 3, 0, 0)) {
            assertEquals(12 - (s.testTo() - s.testFrom()), s.trainIndices().length);
            for (int i : s.trainIndices()) {
                assertTrue(i < s.testFrom() || i >= s.testTo());
            }
        }
    }

    @Test
    void purgedKFoldRefusesDegenerateSetups() {
        assertThrows(IllegalArgumentException.class, () -> PurgedKFold.splits(20, 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> PurgedKFold.splits(5, 3, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> PurgedKFold.splits(20, 4, -1, 0));
        // Horizon so wide the purge eats ALL training data must throw,
        // not silently return an empty training set.
        assertThrows(IllegalArgumentException.class, () -> PurgedKFold.splits(8, 2, 10, 0));
    }

    // ---------------------------------------------------------- OverfitProbability

    @Test
    void genuineSkillScoresPboZero() {
        // Variant 0 beats variant 1 in EVERY period: whatever half is
        // in-sample, the winner is 0 and it ranks 2 of 2 out of sample.
        // omega = 2/3 on every one of C(4,2)=6 splits -> lambda = ln 2 > 0.
        double[][] r = new double[16][2];
        for (int t = 0; t < 16; t++) {
            r[t][0] = 0.01;
            r[t][1] = -0.01;
        }
        OverfitProbability.Result res =
                OverfitProbability.cscv(r, 4, com.quantfinlib.util.MathUtils::mean);
        assertEquals(6, res.combinations());
        assertEquals(0.0, res.pbo(), 0.0);
        for (double lambda : res.logits()) {
            assertEquals(Math.log(2), lambda, 1e-12);
        }
    }

    @Test
    void regimeFlippingNoiseScoresPboOne() {
        // Variant 0 wins the first half of time, variant 1 (its mirror)
        // wins the second half. Whatever blocks you train on, the winner
        // does no better than tie out of sample: every logit <= 0, PBO = 1.
        // +/-1.0 (not 0.01) so mixed-block means cancel EXACTLY in floating
        // point and ties are true ties, not 1e-18 rank noise.
        double[][] r = new double[16][2];
        for (int t = 0; t < 16; t++) {
            r[t][0] = t < 8 ? 1.0 : -1.0;
            r[t][1] = -r[t][0];
        }
        OverfitProbability.Result res =
                OverfitProbability.cscv(r, 4, com.quantfinlib.util.MathUtils::mean);
        assertEquals(6, res.combinations());
        assertEquals(1.0, res.pbo(), 0.0);
    }

    @Test
    void sharpeObjectiveHandlesFlatSeriesAndCountsCombinations() {
        // 3 variants, 8 blocks: C(8,4) = 70 combinations. Variant 2 is
        // flat (zero variance) - the Sharpe objective scores it 0 rather
        // than dividing by zero.
        double[][] r = new double[32][3];
        for (int t = 0; t < 32; t++) {
            r[t][0] = ((t * 7 + 3) % 5 - 2) / 100.0;
            r[t][1] = ((t * 11 + 1) % 7 - 3) / 100.0;
            r[t][2] = 0.0;
        }
        OverfitProbability.Result res = OverfitProbability.cscvSharpe(r, 8);
        assertEquals(70, res.combinations());
        assertTrue(res.pbo() >= 0 && res.pbo() <= 1);
    }

    @Test
    void cscvValidatesItsInputs() {
        double[][] ok = new double[16][2];
        assertThrows(IllegalArgumentException.class,
                () -> OverfitProbability.cscvSharpe(ok, 5));      // odd
        assertThrows(IllegalArgumentException.class,
                () -> OverfitProbability.cscvSharpe(ok, 18));     // > cap
        assertThrows(IllegalArgumentException.class,
                () -> OverfitProbability.cscvSharpe(new double[16][1], 4)); // one variant
        assertThrows(IllegalArgumentException.class,
                () -> OverfitProbability.cscvSharpe(new double[6][2], 4));  // too short
        double[][] ragged = {new double[]{0.1, 0.2}, new double[]{0.1}};
        assertThrows(IllegalArgumentException.class,
                () -> OverfitProbability.cscvSharpe(ragged, 4));
        double[][] nan = new double[16][2];
        nan[3][1] = Double.NaN;
        assertThrows(IllegalArgumentException.class,
                () -> OverfitProbability.cscvSharpe(nan, 4));
    }

    // ---------------------------------------------------------- BenchmarkComparison

    @Test
    void benchmarkComparisonMatchesHandComputedHalfBetaCase() {
        // Strategy = 0.5 * benchmark + 10bp: beta is exactly 0.5, alpha is
        // exactly 10bp * 252, and the capture ratios are exact fractions.
        double[] rb = {0.01, -0.01, 0.02, -0.02};
        double[] rs = new double[4];
        for (int i = 0; i < 4; i++) {
            rs[i] = 0.5 * rb[i] + 0.001;
        }
        BenchmarkComparison.Result r = BenchmarkComparison.compare(rs, rb, 252);

        assertEquals(0.5, r.beta(), 1e-12);
        assertEquals(0.252, r.alpha(), 1e-12);
        assertEquals(0.252, r.activeReturn(), 1e-12);
        // active = 0.001 - 0.5*rb -> sample var = 2.5e-4/3.
        double te = Math.sqrt(2.5e-4 / 3) * Math.sqrt(252);
        assertEquals(te, r.trackingError(), 1e-12);
        assertEquals(0.252 / te, r.informationRatio(), 1e-12);
        assertEquals(17.0 / 30.0, r.upCapture(), 1e-12);   // 0.0085 / 0.015
        assertEquals(13.0 / 30.0, r.downCapture(), 1e-12); // -0.0065 / -0.015
    }

    @Test
    void identicalSeriesHasUnitBetaZeroTrackingError() {
        double[] r = {0.01, -0.02, 0.015, 0.003, -0.007};
        BenchmarkComparison.Result res = BenchmarkComparison.compare(r, r, 252);
        assertEquals(1.0, res.beta(), 1e-12);
        assertEquals(0.0, res.alpha(), 1e-12);
        assertEquals(0.0, res.trackingError(), 1e-12);
        assertEquals(0.0, res.informationRatio(), 1e-12); // 0/0 defined as 0, not NaN
    }

    @Test
    void captureIsNanNotZeroWhenBenchmarkNeverFell() {
        double[] rb = {0.01, 0.02, 0.01, 0.03};
        double[] rs = {0.005, 0.01, 0.02, 0.01};
        BenchmarkComparison.Result r = BenchmarkComparison.compare(rs, rb, 252);
        assertTrue(Double.isNaN(r.downCapture()));
        assertTrue(Double.isFinite(r.upCapture()));
    }

    @Test
    void benchmarkComparisonRefusesBadInput() {
        double[] four = {0.01, -0.01, 0.02, -0.02};
        assertThrows(IllegalArgumentException.class,
                () -> BenchmarkComparison.compare(new double[]{0.1, 0.2}, four, 252));
        assertThrows(IllegalArgumentException.class,
                () -> BenchmarkComparison.compare(four, new double[]{0.01, 0.01, 0.01, 0.01}, 252));
        double[] withNan = {0.01, Double.NaN, 0.02, -0.02};
        assertThrows(IllegalArgumentException.class,
                () -> BenchmarkComparison.compare(withNan, four, 252));
        assertThrows(IllegalArgumentException.class,
                () -> BenchmarkComparison.compare(four, four, 0));
    }

    // ----------------------------------------------------------- DrawdownAnalytics

    @Test
    void drawdownEpisodesMatchHandWalkedCurve() {
        // Peak 110 at i=1, trough 99 at i=2 (10% deep), recovered at i=4;
        // peak 121 at i=5, trough 100 at i=7, still open at series end.
        double[] equity = {100, 110, 99, 104.5, 110, 121, 108.9, 100};
        DrawdownAnalytics.Result r = DrawdownAnalytics.analyze(equity);

        assertEquals(2, r.episodes().size());
        DrawdownAnalytics.Drawdown first = r.episodes().get(0);
        assertEquals(1, first.peakIndex());
        assertEquals(2, first.troughIndex());
        assertEquals(4, first.recoveryIndex());
        assertEquals(0.10, first.depth(), 1e-12);
        assertEquals(3, first.duration(equity.length));

        DrawdownAnalytics.Drawdown second = r.episodes().get(1);
        assertEquals(5, second.peakIndex());
        assertEquals(7, second.troughIndex());
        assertEquals(-1, second.recoveryIndex()); // honest: not recovered
        assertEquals(1 - 100.0 / 121.0, second.depth(), 1e-12);
        assertEquals(2, second.duration(equity.length));

        assertEquals(1 - 100.0 / 121.0, r.maxDepth(), 1e-12);
        assertEquals(3, r.maxDuration());
        assertEquals(0.5, r.timeUnderWater(), 1e-12); // 4 of 8 periods below peak
        // Must agree exactly with the existing max-drawdown estimator.
        assertEquals(RiskMetrics.maxDrawdown(equity), r.maxDepth(), 1e-12);
    }

    @Test
    void monotonicallyRisingEquityHasNoDrawdowns() {
        DrawdownAnalytics.Result r = DrawdownAnalytics.analyze(new double[]{100, 101, 105, 110});
        assertTrue(r.episodes().isEmpty());
        assertEquals(0.0, r.maxDepth(), 0.0);
        assertEquals(0, r.maxDuration());
        assertEquals(0.0, r.timeUnderWater(), 0.0);
    }

    @Test
    void drawdownAnalyticsRefusesNonPositiveEquity() {
        assertThrows(IllegalArgumentException.class,
                () -> DrawdownAnalytics.analyze(new double[]{100}));
        assertThrows(IllegalArgumentException.class,
                () -> DrawdownAnalytics.analyze(new double[]{100, 0, 50}));
        assertThrows(IllegalArgumentException.class,
                () -> DrawdownAnalytics.analyze(new double[]{100, -5, 50}));
        assertThrows(IllegalArgumentException.class,
                () -> DrawdownAnalytics.analyze(new double[]{100, Double.POSITIVE_INFINITY}));
    }
}
