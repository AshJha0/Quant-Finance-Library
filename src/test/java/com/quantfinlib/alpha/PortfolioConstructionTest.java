package com.quantfinlib.alpha;

import com.quantfinlib.core.BarSeries;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Portfolio construction invariants: gross targeting, dollar neutrality,
 * caps, exact sector/beta neutrality after the respective steps, risk
 * budgeting proportions, and the mean-variance tilt using correlation
 * structure that z-scoring ignores.
 */
class PortfolioConstructionTest {

    @Test
    void zScoreWeightsAreDollarNeutralAtTargetGross() {
        double[] scores = {2.0, 1.0, 0.0, -1.0, -2.0, Double.NaN};
        double[] w = PortfolioConstruction.zScoreWeights(scores, 1.0, 1.0);
        assertEquals(1.0, gross(w), 1e-12);          // Σ|w| = grossTarget
        assertEquals(0.0, sum(w), 1e-12);            // demeaned → dollar-neutral
        assertEquals(0.0, w[5]);                     // NaN score → flat, never traded
        assertTrue(w[0] > w[1] && w[1] > w[2] && w[2] > w[3] && w[3] > w[4]);
        // Degenerate inputs hold cash instead of fabricating a book.
        assertEquals(0, gross(PortfolioConstruction.zScoreWeights(
                new double[]{3, 3, 3}, 1.0, 1.0)), 1e-12);
        assertThrows(IllegalArgumentException.class,
                () -> PortfolioConstruction.zScoreWeights(scores, 0, 0.1));
    }

    @Test
    void outliersAreWinsorizedAndCapsRespected() {
        // One absurd score must not own the book: z is clamped at ±3.
        double[] scores = {1000.0, 1.0, 0.5, -0.5, -1.0};
        double[] w = PortfolioConstruction.zScoreWeights(scores, 1.0, 0.30);
        for (double x : w) {
            assertTrue(Math.abs(x) <= 0.30 + 1e-12, "cap breached: " + x);
        }
        assertEquals(0.30, Math.abs(w[0]), 1e-9); // the outlier sits AT the cap
    }

    @Test
    void inverseVolBudgetEqualizesRiskContributions() {
        double[] w = {0.25, -0.25, 0.25, -0.25};
        double[] vols = {0.01, 0.04, 0.02, 0.02};
        double[] out = PortfolioConstruction.inverseVolBudget(w, vols, 1.0);
        assertEquals(1.0, gross(out), 1e-12);
        // |w|·σ equal across names: each contributes the same first-order risk.
        double risk0 = Math.abs(out[0]) * vols[0];
        for (int i = 1; i < out.length; i++) {
            assertEquals(risk0, Math.abs(out[i]) * vols[i], 1e-12);
        }
        // Signs survive the rescale.
        assertTrue(out[0] > 0 && out[1] < 0);
        assertThrows(IllegalArgumentException.class,
                () -> PortfolioConstruction.inverseVolBudget(w, new double[]{0.01, 0, 0.02, 0.02}, 1));
    }

    @Test
    void sectorNeutralizationZeroesEverySectorNet() {
        double[] w = {0.30, 0.10, -0.05, 0.20, -0.40, 0.0};
        String[] sectors = {"TECH", "TECH", "TECH", "FIN", "FIN", "FIN"};
        double[] out = PortfolioConstruction.sectorNeutralize(w, sectors);
        assertEquals(0.0, out[0] + out[1] + out[2], 1e-12); // TECH nets to zero
        assertEquals(0.0, out[3] + out[4], 1e-12);          // FIN nets to zero
        assertEquals(0.0, out[5]);                          // flat names stay flat
    }

    @Test
    void betaNeutralizationZeroesPortfolioBeta() {
        double[] w = {0.40, 0.30, -0.30, -0.40};
        double[] betas = {1.5, 1.0, 0.8, 0.5};
        double[] out = PortfolioConstruction.betaNeutralize(w, betas);
        double portfolioBeta = 0;
        for (int i = 0; i < out.length; i++) {
            portfolioBeta += out[i] * betas[i];
        }
        assertEquals(0.0, portfolioBeta, 1e-12); // Σ wβ = 0 exactly
        assertThrows(IllegalArgumentException.class,
                () -> PortfolioConstruction.betaNeutralize(w, new double[]{1, Double.NaN, 1, 1}));
    }

    @Test
    void trailingBetasRecoverTheLeveragedName() {
        // HI moves 2× the market proxy, LO 0.5× — betas must recover that.
        Map<String, BarSeries> data = new HashMap<>();
        double[] marketMoves = new double[60];
        java.util.Random rng = new java.util.Random(11);
        for (int i = 0; i < 60; i++) {
            marketMoves[i] = (rng.nextDouble() - 0.5) * 0.02;
        }
        data.put("HI", follow("HI", marketMoves, 2.0));
        data.put("LO", follow("LO", marketMoves, 0.5));
        AlphaContext ctx = AlphaContext.of(data);
        double[] betas = PortfolioConstruction.trailingBetas(ctx, 59, 50);
        int hi = ctx.symbols().indexOf("HI");
        int lo = ctx.symbols().indexOf("LO");
        // Betas vs the EQUAL-WEIGHT panel return (mean beta 1 by construction):
        // 2x and 0.5x names land at 2/1.25 = 1.6 and 0.5/1.25 = 0.4.
        assertEquals(1.6, betas[hi], 0.01);
        assertEquals(0.4, betas[lo], 0.01);
    }

    private static BarSeries follow(String symbol, double[] moves, double beta) {
        BarSeries.Builder b = BarSeries.builder(symbol);
        double close = 100;
        for (int i = 0; i < moves.length; i++) {
            double open = close;
            close = open * (1 + beta * moves[i]);
            b.add(i, open, Math.max(open, close), Math.min(open, close), close, 1_000);
        }
        return b.build();
    }

    @Test
    void meanVarianceTiltUsesTheCovarianceStructure() {
        // Equal alphas, one name 4× the variance: Σ⁻¹α halves its weight
        // relative to z-scoring, which would treat all three identically.
        double[] alphas = {1.0, 1.0, 1.0, Double.NaN};
        double[][] cov = {
                {0.04, 0, 0, 0},
                {0, 0.01, 0, 0},
                {0, 0, 0.01, 0},
                {0, 0, 0, 0.01}};
        double[] w = PortfolioConstruction.meanVarianceTilt(alphas, cov, 1.0);
        assertEquals(1.0, gross(w), 1e-12);
        // Diagonal cov → w ∝ α/σ²: the high-var name gets a quarter the weight.
        assertEquals(w[1] / 4, w[0], 1e-12);
        assertEquals(w[1], w[2], 1e-12);
        assertEquals(0.0, w[3]); // NaN alpha excluded entirely
        assertThrows(IllegalArgumentException.class,
                () -> PortfolioConstruction.meanVarianceTilt(alphas, new double[2][2], 1.0));
    }

    private static double gross(double[] w) {
        double g = 0;
        for (double x : w) {
            g += Math.abs(x);
        }
        return g;
    }

    private static double sum(double[] w) {
        double s = 0;
        for (double x : w) {
            s += x;
        }
        return s;
    }
}
