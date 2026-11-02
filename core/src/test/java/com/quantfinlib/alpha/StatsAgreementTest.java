package com.quantfinlib.alpha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quantfinlib.risk.Dependence;
import com.quantfinlib.stats.Stats;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * The alpha package keeps two correlation helpers with conventions of their
 * own (pairwise-complete, NaN below three pairs). These tests pin that they
 * agree with the canonical {@link Stats} estimators wherever those
 * conventions do not apply, and differ only where documented.
 */
class StatsAgreementTest {

    private static double[][] pair(long seed, int n) {
        SplittableRandom rnd = new SplittableRandom(seed);
        double[] a = new double[n];
        double[] b = new double[n];
        for (int i = 0; i < n; i++) {
            a[i] = rnd.nextGaussian();
            b[i] = 0.6 * a[i] + 0.8 * rnd.nextGaussian();
            if (i % 5 == 0) {
                b[i] = Math.rint(b[i]);   // ties
            }
        }
        return new double[][] {a, b};
    }

    @Test
    void combinerPearsonAgreesWithCanonicalOnCompleteData() {
        for (long seed = 1; seed <= 5; seed++) {
            double[][] p = pair(seed, 3 + (int) seed * 20);
            assertEquals(Stats.pearson(p[0], p[1]), AlphaCombiner.pearson(p[0], p[1]), 1e-12);
        }
    }

    @Test
    void combinerPearsonDiffersOnlyWhereDocumented() {
        // Fewer than three complete pairs: NaN, where the canonical estimator still answers.
        assertTrue(Double.isNaN(AlphaCombiner.pearson(new double[] {1, 2}, new double[] {2, 1})));
        assertEquals(-1, Stats.pearson(new double[] {1, 2}, new double[] {2, 1}), 1e-15);
        // Pairwise-complete: a NaN pair is dropped, the canonical estimator propagates it.
        double[] a = {1, 2, 3, Double.NaN, 5};
        double[] b = {2, 4, 5, 9, 4};
        assertEquals(Stats.pearson(new double[] {1, 2, 3, 5}, new double[] {2, 4, 5, 4}),
                AlphaCombiner.pearson(a, b), 1e-15);
        assertTrue(Double.isNaN(Stats.pearson(a, b)));
    }

    @Test
    void evaluatorSpearmanAgreesWithDependenceSpearman() {
        for (long seed = 11; seed <= 15; seed++) {
            double[][] p = pair(seed, 50);
            assertEquals(Dependence.spearman(p[0], p[1]), SignalEvaluator.spearman(p[0], p[1]), 0);
            assertEquals(Stats.pearson(Stats.ranks(p[0]), Stats.ranks(p[1])),
                    SignalEvaluator.spearman(p[0], p[1]), 1e-15);
        }
    }
}
