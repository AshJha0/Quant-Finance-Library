package com.quantfinlib.alpha;

import com.quantfinlib.core.BarSeries;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IC-weighted combination: a planted panel where returns load on a fast
 * (one-bar) and a slow (AR(1), φ = 0.95) signal, a redundant copy of the
 * fast one and pure noise.
 */
class AlphaCombinerTest {

    static final int N = 60;
    static final int T = 700;

    static final class World {
        AlphaContext ctx;
        double[][] fast = new double[T][N];
        double[][] slow = new double[T][N];
        double[][] noise = new double[T][N];
        double[][] copy = new double[T][N];
    }

    static World world(long seed) {
        SplittableRandom rnd = new SplittableRandom(seed);
        World w = new World();
        double[] px = new double[N];
        java.util.Arrays.fill(px, 100);
        BarSeries.Builder[] b = new BarSeries.Builder[N];
        for (int i = 0; i < N; i++) {
            b[i] = BarSeries.builder(String.format("S%02d", i));
            b[i].add(0, 100, 100, 100, 100, 1e6);
        }
        double phi = 0.95;
        for (int t = 0; t < T; t++) {
            for (int i = 0; i < N; i++) {
                w.fast[t][i] = AlphaStatisticsTest.gaussian(rnd);
                w.slow[t][i] = t == 0 ? AlphaStatisticsTest.gaussian(rnd)
                        : phi * w.slow[t - 1][i]
                        + Math.sqrt(1 - phi * phi) * AlphaStatisticsTest.gaussian(rnd);
                w.noise[t][i] = AlphaStatisticsTest.gaussian(rnd);
                w.copy[t][i] = w.fast[t][i] + 0.05 * AlphaStatisticsTest.gaussian(rnd);
            }
            if (t + 1 < T) {
                for (int i = 0; i < N; i++) {
                    double r = 0.004 * w.fast[t][i] + 0.002 * w.slow[t][i]
                            + 0.02 * AlphaStatisticsTest.gaussian(rnd);
                    double o = px[i];
                    px[i] *= 1 + r;
                    b[i].add(t + 1, o, Math.max(o, px[i]), Math.min(o, px[i]), px[i], 1e6);
                }
            }
        }
        Map<String, BarSeries> data = new LinkedHashMap<>();
        for (int i = 0; i < N; i++) {
            data.put(String.format("S%02d", i), b[i].build());
        }
        w.ctx = AlphaContext.of(data);
        return w;
    }

    static AlphaFactor of(double[][] s, String name) {
        return new AlphaFactor() {
            @Override
            public double[] scores(AlphaContext ctx, int index) {
                return s[index].clone();
            }

            @Override
            public String name() {
                return name;
            }
        };
    }

    @Test
    void horizonMatchingFlipsFastAndSlowSignals() {
        World w = world(1);
        List<AlphaFactor> fs = List.of(of(w.fast, "FAST"), of(w.slow, "SLOW"));
        AlphaCombiner.Combination h1 = AlphaCombiner.fit(w.ctx, fs, 0, T - 1,
                AlphaCombiner.Settings.of(1).withOrthogonalize(false));
        AlphaCombiner.Combination h20 = AlphaCombiner.fit(w.ctx, fs, 0, T - 1,
                AlphaCombiner.Settings.of(20).withOrthogonalize(false));
        // Expected ICs with return sd ~ 0.0205: fast 0.195 at H=1 and 0.195/20 at H=20;
        // slow 0.098 at H=1 and 0.098 (1 - 0.95^20)/(20 * 0.05) ~ 0.063 at H=20.
        assertEquals(0.195, h1.ic(0), 0.03);
        assertEquals(0.098, h1.ic(1), 0.03);
        assertEquals(0.0098, h20.ic(0), 0.01);
        assertEquals(0.063, h20.ic(1), 0.02);
        assertTrue(h1.weight(0) > h1.weight(1));
        assertTrue(h20.weight(1) > 3 * h20.weight(0));
        assertEquals(1, Math.abs(h1.weight(0)) + Math.abs(h1.weight(1)), 1e-12);
    }

    @Test
    void orthogonalisationRemovesARedundantCopy() {
        World w = world(2);
        List<AlphaFactor> fs = List.of(of(w.fast, "FAST"), of(w.copy, "COPY"),
                of(w.noise, "NOISE"));
        AlphaCombiner.Combination raw = AlphaCombiner.fit(w.ctx, fs, 0, T - 1,
                AlphaCombiner.Settings.of(1).withOrthogonalize(false));
        AlphaCombiner.Combination orth = AlphaCombiner.fit(w.ctx, fs, 0, T - 1,
                AlphaCombiner.Settings.of(1));
        // without orthogonalisation the copy takes about as much weight as the original
        assertEquals(raw.weight(0), raw.weight(1), 0.05);
        // with it the copy keeps only its 5% noise: IC near zero, weight small
        assertTrue(Math.abs(orth.ic(1)) < 0.02, "copy ic " + orth.ic(1));
        assertTrue(orth.weight(0) > 0.8, "fast weight " + orth.weight(0));
        assertTrue(Math.abs(orth.weight(2)) < 0.12);
        // the combined score at a date: orthogonalised z-scores weighted
        double[] combo = orth.scores(w.ctx, 100);
        double[][] z = AlphaCombiner.prepared(w.ctx, fs, 100, true);
        for (int i = 0; i < N; i += 7) {
            double expected = 0;
            for (int k = 0; k < 3; k++) {
                expected += orth.weight(k) * z[k][i];
            }
            assertEquals(expected, combo[i], 1e-12);
        }
        // Gram-Schmidt output is orthogonal and unit-variance per date
        double dot = 0;
        double ss = 0;
        for (int i = 0; i < N; i++) {
            dot += z[0][i] * z[1][i];
            ss += z[1][i] * z[1][i];
        }
        assertEquals(0, dot, 1e-9);
        assertEquals(N, ss, 1e-9);
        assertTrue(orth.name().startsWith("COMBO(FAST:"));
        assertEquals(3, orth.factorCount());
        assertEquals(T - 1, orth.dates());
    }

    @Test
    void icMatchesAnIndependentComputation() {
        World w = world(3);
        List<AlphaFactor> fs = List.of(of(w.slow, "SLOW"));
        int h = 3;
        AlphaCombiner.Combination c = AlphaCombiner.fit(w.ctx, fs, 10, 200,
                new AlphaCombiner.Settings(h, 25, 0, 5, false));
        // recompute: dates 10, 15, ..., <= 197; per-date average Pearson IC over 3 lags;
        // exponential weights with half-life 25 dates, newest weight 1
        java.util.List<Double> ics = new java.util.ArrayList<>();
        for (int t = 10; t <= 200 - h; t += 5) {
            double s = 0;
            for (int l = 0; l < h; l++) {
                double[] x = w.slow[t];
                double[] y = new double[N];
                for (int i = 0; i < N; i++) {
                    y[i] = w.ctx.series(i).close(t + l + 1) / w.ctx.series(i).close(t + l) - 1;
                }
                s += com.quantfinlib.util.MathUtils.correlation(x, y) / h;
            }
            ics.add(s);
        }
        double num = 0;
        double den = 0;
        double wt = 1;
        double d = Math.pow(0.5, 1 / 25.0);
        for (int k = ics.size() - 1; k >= 0; k--, wt *= d) {
            num += wt * ics.get(k);
            den += wt;
        }
        assertEquals(num / den, c.ic(0), 1e-12);
        assertEquals(ics.size(), c.dates());
        assertEquals(1, c.weight(0), 1e-12);
    }

    @Test
    void shrinkageMovesTowardEqualWeights() {
        World w = world(4);
        List<AlphaFactor> fs = List.of(of(w.fast, "FAST"), of(w.slow, "SLOW"),
                of(w.noise, "NOISE"));
        AlphaCombiner.Settings s = AlphaCombiner.Settings.of(1);
        AlphaCombiner.Combination none = AlphaCombiner.fit(w.ctx, fs, 0, 400, s);
        AlphaCombiner.Combination full = AlphaCombiner.fit(w.ctx, fs, 0, 400,
                s.withShrinkToEqual(1));
        AlphaCombiner.Combination half = AlphaCombiner.fit(w.ctx, fs, 0, 400,
                s.withShrinkToEqual(0.5));
        for (int k = 0; k < 3; k++) {
            assertEquals(1.0 / 3, full.weight(k), 1e-15);
            assertEquals(0.5 * none.weight(k) + 0.5 / 3, half.weight(k), 1e-15);
        }
        AlphaCombiner.Combination recent = AlphaCombiner.fit(w.ctx, fs, 0, 400,
                s.withIcHalfLife(20));
        assertTrue(recent.ic(0) != none.ic(0));
        assertEquals(3, none.icsCopy().length);
        assertEquals(3, none.weightsCopy().length);
    }

    @Test
    void rejectsBadInput() {
        World w = world(5);
        assertThrows(IllegalArgumentException.class, () -> new AlphaCombiner.Settings(0, 1, 0, 1,
                true));
        assertThrows(IllegalArgumentException.class, () -> new AlphaCombiner.Settings(1, 1, 2, 1,
                true));
        assertThrows(IllegalArgumentException.class, () -> AlphaCombiner.fit(w.ctx, List.of(),
                0, 10, AlphaCombiner.Settings.of(1)));
        assertThrows(IllegalArgumentException.class, () -> AlphaCombiner.fit(w.ctx,
                List.of(of(w.fast, "F")), 10, 10, AlphaCombiner.Settings.of(1)));
        assertTrue(Double.isNaN(AlphaCombiner.pearson(new double[]{1, 2}, new double[]{1, 2})));
    }
}
