package com.quantfinlib.alpha;

import com.quantfinlib.core.BarSeries;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The overfitting defenses: walk-forward picks the genuinely better
 * candidate on training data and holds up out of sample, blocked CV shows
 * regime consistency, the permutation test separates real signals from
 * static luck, and sensitivity distinguishes plateaus from spikes.
 */
class AlphaValidationTest {

    private static final int BARS = 300;
    private static final double[] DRIFTS = {0.004, 0.003, 0.002, 0.001,
            -0.001, -0.002, -0.003, -0.004};

    private static AlphaContext panel() {
        Map<String, BarSeries> data = new HashMap<>();
        for (int s = 0; s < DRIFTS.length; s++) {
            BarSeries.Builder b = BarSeries.builder("S" + s);
            double close = 100;
            for (int i = 0; i < BARS; i++) {
                double open = close;
                close = 100 * Math.pow(1 + DRIFTS[s], i + 1);
                b.add(i, open, Math.max(open, close), Math.min(open, close), close, 1_000);
            }
            data.put("S" + s, b.build());
        }
        return AlphaContext.of(data);
    }

    /** A candidate that anti-ranks the panel — the trap walk-forward must avoid. */
    private static AlphaFactor sabotage() {
        return new AlphaFactor() {
            @Override
            public double[] scores(AlphaContext ctx, int index) {
                double[] s = Factors.momentum(20, 0).scores(ctx, index);
                for (int i = 0; i < s.length; i++) {
                    s[i] = -s[i];
                }
                return s;
            }

            @Override
            public String name() {
                return "SABOTAGE";
            }
        };
    }

    @Test
    void walkForwardPicksTheRealSignalInEveryFold() {
        AlphaValidation.WalkForwardResult r = AlphaValidation.walkForward(
                panel(), List.of(sabotage(), Factors.momentum(20, 0)),
                5, 30, 80, 40);
        assertTrue(r.folds().size() >= 3);
        for (AlphaValidation.Fold fold : r.folds()) {
            // Training IC must select momentum over the anti-signal...
            assertEquals("MOMENTUM(20-0)", fold.chosenFactor());
            // ...and the choice must hold up on the unseen window.
            assertTrue(fold.outOfSampleIc() > 0.9, "OOS IC " + fold.outOfSampleIc());
        }
        // Stationary panel: no overfitting gap, efficiency ≈ 1.
        assertEquals(1.0, r.efficiency(), 0.05);
        assertTrue(r.meanOutOfSampleIc() > 0.9);
    }

    @Test
    void blockedCrossValidationShowsRegimeConsistency() {
        AlphaValidation.CrossValidationResult r = AlphaValidation.crossValidate(
                panel(), Factors.momentum(20, 0), 5, 30, 4);
        assertEquals(4, r.blockIcs().length);
        // A stationary signal is consistent in every block.
        assertEquals(1.0, r.signConsistency(), 1e-12);
        assertEquals(1.0, r.meanIc(), 1e-9);
        assertTrue(r.icStd() < 1e-9);
        assertThrows(IllegalArgumentException.class,
                () -> AlphaValidation.crossValidate(panel(), Factors.momentum(20, 0), 5, 30, 1));
    }

    /**
     * Drifts flip sign halfway: the momentum ranking genuinely changes over
     * time (and keeps predicting), so the date-permutation has something to
     * destroy. On a time-INVARIANT panel the test is deliberately
     * conservative — one effective observation — which the luck case below
     * exercises.
     */
    private static AlphaContext regimePanel() {
        Map<String, BarSeries> data = new HashMap<>();
        for (int s = 0; s < DRIFTS.length; s++) {
            BarSeries.Builder b = BarSeries.builder("S" + s);
            double close = 100;
            for (int i = 0; i < BARS; i++) {
                double open = close;
                double drift = i < BARS / 2 ? DRIFTS[s] : -DRIFTS[s];
                close = open * (1 + drift);
                b.add(i, open, Math.max(open, close), Math.min(open, close), close, 1_000);
            }
            data.put("S" + s, b.build());
        }
        return AlphaContext.of(data);
    }

    @Test
    void permutationTestSeparatesSignalFromStaticLuck() {
        AlphaContext ctx = regimePanel();
        // The real signal rides both regimes; cross-regime permuted pairings
        // anti-correlate, so the null distribution sits far below.
        AlphaValidation.RobustnessResult real = AlphaValidation.monteCarloRobustness(
                ctx, Factors.momentum(20, 0), 5, 30, 200, 42);
        assertTrue(real.pValue() < 0.05, "real signal p=" + real.pValue());
        assertTrue(real.observedMeanIc() > 0.7, "IC=" + real.observedMeanIc());

        // Static random scores: whatever IC they luck into is IDENTICAL at
        // every date, so permuting dates changes nothing → p ≈ 1. The test
        // correctly refuses to call time-invariant luck a signal.
        Random rng = new Random(7);
        double[] fixed = new double[ctx.symbolCount()];
        for (int i = 0; i < fixed.length; i++) {
            fixed[i] = rng.nextDouble() - 0.5;
        }
        AlphaFactor luck = (c, index) -> fixed.clone();
        AlphaValidation.RobustnessResult lucky = AlphaValidation.monteCarloRobustness(
                ctx, luck, 5, 30, 200, 42);
        assertEquals(1.0, lucky.pValue(), 1e-9);
        assertThrows(IllegalArgumentException.class,
                () -> AlphaValidation.monteCarloRobustness(ctx, luck, 5, 30, 5, 42));
    }

    @Test
    void trainingWindowsNeverReadTestReturns() {
        // The leakage regression: meanIc over [from, to) must only evaluate
        // dates whose ENTIRE forward window (t, t+h] fits inside the range —
        // otherwise walk-forward selection peeks at test-window returns.
        java.util.List<Integer> asked = new java.util.ArrayList<>();
        AlphaFactor recording = new AlphaFactor() {
            @Override
            public double[] scores(AlphaContext ctx, int index) {
                asked.add(index);
                return Factors.momentum(20, 0).scores(ctx, index);
            }
        };
        int from = 30;
        int to = 90;
        int horizon = 25;
        AlphaValidation.meanIc(panel(), recording, from, to, horizon);
        assertTrue(!asked.isEmpty());
        for (int t : asked) {
            assertTrue(t + horizon < to,
                    "date " + t + " forward window crosses the boundary " + to);
        }
    }

    @Test
    void walkForwardRefusesWhenNoCandidateScoresInTraining() {
        // Warm-up (200 bars) exceeds the training window: every training IC
        // is NaN, and the fold must fail with a diagnostic, not an NPE.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AlphaValidation.walkForward(panel(),
                        List.of(Factors.momentum(250, 0)), 5, 0, 80, 40));
        assertTrue(e.getMessage().contains("warm-up"), e.getMessage());
    }

    @Test
    void sensitivitySweepShowsAPlateauNotASpike() {
        List<AlphaFactor> sweep = List.of(
                Factors.momentum(15, 0), Factors.momentum(20, 0),
                Factors.momentum(25, 0), Factors.momentum(30, 0));
        AlphaValidation.SensitivityResult r = AlphaValidation.parameterSensitivity(
                panel(), sweep, 5, 40);
        // On a stationary drift panel every lookback ranks identically:
        // the flattest possible plateau — the robust-signal signature.
        assertEquals(4, r.meanIcs().length);
        for (double ic : r.meanIcs()) {
            assertEquals(1.0, ic, 1e-9);
        }
        assertEquals(0.0, r.worstNeighborDrop(), 1e-9);
        assertTrue(r.best().startsWith("MOMENTUM"));
        assertThrows(IllegalArgumentException.class,
                () -> AlphaValidation.parameterSensitivity(panel(), List.of(sweep.get(0)), 5, 40));
    }
}
