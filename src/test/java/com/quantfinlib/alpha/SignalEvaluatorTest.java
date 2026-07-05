package com.quantfinlib.alpha;

import com.quantfinlib.core.BarSeries;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Signal evaluation on a panel with known cross-sectional structure: a
 * factor that ranks by true drift earns IC ≈ +1, its negation ≈ −1, hit
 * rate follows signs, turnover distinguishes stable from flipping signals,
 * and factor exposure detects disguised duplicates.
 */
class SignalEvaluatorTest {

    private static final int BARS = 200;
    // Eight symbols with distinct drifts, half positive half negative —
    // forward returns rank exactly by drift, so a drift-ranking factor is
    // a perfect signal by construction.
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

    /** Scores each symbol by its true drift — the oracle factor. */
    private static AlphaFactor oracle() {
        return new AlphaFactor() {
            @Override
            public double[] scores(AlphaContext ctx, int index) {
                double[] s = new double[ctx.symbolCount()];
                for (int i = 0; i < s.length; i++) {
                    // Symbols sort as S0..S7, so drift index = symbol index.
                    s[i] = DRIFTS[Integer.parseInt(ctx.symbols().get(i).substring(1))];
                }
                return s;
            }

            @Override
            public String name() {
                return "ORACLE";
            }
        };
    }

    @Test
    void perfectSignalEarnsIcNearOne() {
        SignalEvaluator.Report r = SignalEvaluator.evaluate(panel(), oracle(), 10, 5);
        assertEquals(1.0, r.meanIc(), 1e-9);          // exact rank agreement
        assertEquals(0.0, r.icStd(), 1e-9);
        assertEquals(1.0, r.hitRate(), 1e-9);          // every sign call right
        assertEquals(0.0, r.meanTurnover(), 1e-9);     // static scores: no trading
        assertTrue(r.observations() > 10);
        assertTrue(r.format().contains("ORACLE"));
        // IC series is exposed for downstream plotting/validation.
        assertEquals(r.observations(), r.icSeries().length);
    }

    @Test
    void invertedSignalEarnsIcNearMinusOne() {
        AlphaFactor inverted = (ctx, index) -> {
            double[] s = oracle().scores(ctx, index);
            for (int i = 0; i < s.length; i++) {
                s[i] = -s[i];
            }
            return s;
        };
        SignalEvaluator.Report r = SignalEvaluator.evaluate(panel(), inverted, 10, 5);
        assertEquals(-1.0, r.meanIc(), 1e-9);
        assertEquals(0.0, r.hitRate(), 1e-9); // every sign call wrong
    }

    @Test
    void flippingSignalShowsFullTurnover() {
        // Same ranking, sign flipped every evaluation date: information
        // identical in magnitude, but the book reverses entirely each time.
        AlphaFactor flipper = new AlphaFactor() {
            @Override
            public double[] scores(AlphaContext ctx, int index) {
                double[] s = oracle().scores(ctx, index);
                double sign = (index / 5) % 2 == 0 ? 1 : -1;
                for (int i = 0; i < s.length; i++) {
                    s[i] *= sign;
                }
                return s;
            }
        };
        SignalEvaluator.Report r = SignalEvaluator.evaluate(panel(), flipper, 10, 5);
        // Reversing a gross-1 dollar-neutral book trades 2.0 L1 → turnover 1.0.
        assertEquals(1.0, r.meanTurnover(), 1e-9);
        // And the IC averages to ~0 (exactly ±1/n from the odd date count) —
        // high turnover bought nothing.
        assertEquals(0.0, r.meanIc(), 0.05);
    }

    @Test
    void factorExposureDetectsDuplicatesAndInverses() {
        AlphaContext ctx = panel();
        AlphaFactor momentum = Factors.momentum(20, 0);
        // Momentum on this panel IS the drift ranking: exposure ≈ +1.
        assertEquals(1.0, SignalEvaluator.factorExposure(ctx, oracle(), momentum, 30, 10), 1e-9);
        AlphaFactor inverted = (c, i) -> {
            double[] s = oracle().scores(c, i);
            for (int j = 0; j < s.length; j++) {
                s[j] = -s[j];
            }
            return s;
        };
        assertEquals(-1.0, SignalEvaluator.factorExposure(ctx, oracle(), inverted, 30, 10), 1e-9);
    }

    @Test
    void nanScoresAreDroppedPairwiseNotFabricated() {
        // Only two scored symbols → fewer than 3 complete pairs → no IC
        // observations → evaluation refuses rather than reporting noise.
        AlphaFactor sparse = (ctx, index) -> {
            double[] s = new double[ctx.symbolCount()];
            java.util.Arrays.fill(s, Double.NaN);
            s[0] = 1;
            s[1] = -1;
            return s;
        };
        assertThrows(IllegalArgumentException.class,
                () -> SignalEvaluator.evaluate(panel(), sparse, 10, 5));
        assertThrows(IllegalArgumentException.class,
                () -> SignalEvaluator.evaluate(panel(), oracle(), 10, 0));
    }
}
