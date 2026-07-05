package com.quantfinlib.alpha;

import com.quantfinlib.core.BarSeries;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The execution-aware backtest (a predictive factor profits gross, every
 * cost component bites, impact scales with capital) and the reporting layer
 * (alpha decay with finite/infinite half-life, exact OLS attribution,
 * drawdown and rolling-Sharpe curves).
 */
class AlphaBacktesterReportTest {

    private static final int BARS = 260;
    private static final double[] DRIFTS = {0.004, 0.002, 0.001, -0.001, -0.002, -0.004};

    private static AlphaContext panel() {
        Map<String, BarSeries> data = new HashMap<>();
        for (int s = 0; s < DRIFTS.length; s++) {
            BarSeries.Builder b = BarSeries.builder("S" + s);
            double close = 100;
            for (int i = 0; i < BARS; i++) {
                double open = close;
                close = 100 * Math.pow(1 + DRIFTS[s], i + 1);
                b.add(i, open, Math.max(open, close), Math.min(open, close), close, 500_000);
            }
            data.put("S" + s, b.build());
        }
        return AlphaContext.of(data);
    }

    /**
     * Drift plus seeded per-bar noise: the impact model needs REAL return
     * volatility (the square-root law scales with σ — a noiseless drift
     * panel has σ = 0 and correctly charges ~no impact).
     */
    private static AlphaContext noisyPanel() {
        java.util.Random rng = new java.util.Random(99);
        Map<String, BarSeries> data = new HashMap<>();
        for (int s = 0; s < DRIFTS.length; s++) {
            BarSeries.Builder b = BarSeries.builder("S" + s);
            double close = 100;
            for (int i = 0; i < BARS; i++) {
                double open = close;
                close = open * (1 + DRIFTS[s] + 0.004 * (rng.nextDouble() - 0.5));
                b.add(i, open, Math.max(open, close), Math.min(open, close), close, 500_000);
            }
            data.put("S" + s, b.build());
        }
        return AlphaContext.of(data);
    }

    // ------------------------------------------------------------------
    // AlphaBacktester
    // ------------------------------------------------------------------

    @Test
    void predictiveFactorProfitsAndCostsBite() {
        AlphaContext ctx = noisyPanel();
        AlphaBacktester.Config config = new AlphaBacktester.Config(
                30, 21, 1.0, 2.0, 1.0, 100_000_000, 20, 252);
        AlphaBacktester.Result r = AlphaBacktester.run(ctx, Factors.momentum(20, 0), config);

        // Long winners / short losers on a drift panel: gross must profit.
        double grossFinal = r.grossEquity()[r.grossEquity().length - 1];
        double netFinal = r.netEquity()[r.netEquity().length - 1];
        assertTrue(grossFinal > 1.0, "gross=" + grossFinal);
        // Every cost component was charged, and net < gross by construction.
        assertTrue(netFinal < grossFinal);
        assertTrue(r.commissionDrag() > 0);
        assertTrue(r.spreadDrag() > 0);
        assertTrue(r.slippageDrag() > 0);
        assertTrue(r.impactDrag() > 0);
        assertEquals(r.commissionDrag() + r.spreadDrag() + r.slippageDrag() + r.impactDrag(),
                r.totalCostDrag(), 1e-12);
        // Metrics computed on both curves by the shared engine.
        assertTrue(r.netMetrics().sharpeRatio() > 0);
        assertTrue(r.grossMetrics().sharpeRatio() >= r.netMetrics().sharpeRatio());
        // Ranks are drift-anchored: rebalances shuffle the noisy middle but
        // never rebuild the book from scratch.
        assertTrue(r.meanTurnover() < 0.5, "turnover=" + r.meanTurnover());
    }

    @Test
    void impactScalesWithCapitalAndDisablesAtZero() {
        AlphaContext ctx = noisyPanel();
        AlphaBacktester.Config small = new AlphaBacktester.Config(
                30, 21, 1.0, 2.0, 1.0, 0, 20, 252);          // capital 0: no impact
        AlphaBacktester.Config big = new AlphaBacktester.Config(
                30, 21, 1.0, 2.0, 1.0, 1_000_000_000, 20, 252); // $1b book
        AlphaBacktester.Result noImpact = AlphaBacktester.run(ctx, Factors.momentum(20, 0), small);
        AlphaBacktester.Result bigBook = AlphaBacktester.run(ctx, Factors.momentum(20, 0), big);
        assertEquals(0.0, noImpact.impactDrag());
        assertTrue(bigBook.impactDrag() > 0);
        // Same signal, same flat costs — size alone degrades the net result:
        // the square-root law making "capacity" a number, not a slogan.
        assertTrue(bigBook.netEquity()[bigBook.netEquity().length - 1]
                < noImpact.netEquity()[noImpact.netEquity().length - 1]);
        assertThrows(IllegalArgumentException.class,
                () -> new AlphaBacktester.Config(-1, 21, 1, 2, 1, 0, 20, 252));
        assertThrows(IllegalArgumentException.class, () -> AlphaBacktester.run(
                ctx, Factors.momentum(20, 0), config(BARS - 1)));
    }

    private static AlphaBacktester.Config config(int start) {
        return new AlphaBacktester.Config(start, 21, 1, 2, 1, 0, 20, 252);
    }

    @Test
    void customConstructionPipelinePlugsIn() {
        AlphaContext ctx = noisyPanel();
        // Full pipeline: z-score → beta-neutralize → inverse-vol budget.
        AlphaBacktester.Result r = AlphaBacktester.run(ctx, Factors.momentum(20, 0),
                config(30), (c, scores, index) -> {
                    double[] w = PortfolioConstruction.zScoreWeights(scores, 1.0, 0.5);
                    w = PortfolioConstruction.betaNeutralize(w,
                            PortfolioConstruction.trailingBetas(c, index, 25));
                    return PortfolioConstruction.inverseVolBudget(w,
                            PortfolioConstruction.trailingVols(c, index, 25), 1.0);
                });
        assertTrue(r.netEquity()[r.netEquity().length - 1] > 1.0);
        // A misaligned builder is rejected, not silently truncated.
        assertThrows(IllegalArgumentException.class, () -> AlphaBacktester.run(
                ctx, Factors.momentum(20, 0), config(30),
                (c, scores, index) -> new double[1]));
    }

    // ------------------------------------------------------------------
    // AlphaReport
    // ------------------------------------------------------------------

    @Test
    void decayIsFlatForPersistentSignalsAndFiniteForFadingOnes() {
        // Persistent: drift panel momentum never decays → infinite half-life.
        AlphaReport.Decay persistent = AlphaReport.decayProfile(
                panel(), Factors.momentum(20, 0), 30, new int[]{1, 2, 5, 10});
        for (double ic : persistent.meanIcs()) {
            assertEquals(1.0, ic, 1e-9);
        }
        assertTrue(Double.isInfinite(persistent.halfLifeBars()));
        assertTrue(persistent.format().contains("half-life"));

        // Fading: zigzag panel — mean reversion predicts the next bar
        // exactly (the zigzag flips), but two bars out the zigzag cancels
        // and only the small random drift remains → IC collapses.
        AlphaReport.Decay fading = AlphaReport.decayProfile(
                zigzagPanel(), Factors.meanReversion(2), 10, new int[]{1, 2});
        assertTrue(fading.meanIcs()[0] > 0.9, "h=1 IC " + fading.meanIcs()[0]);
        assertTrue(Math.abs(fading.meanIcs()[1]) < 0.5, "h=2 IC " + fading.meanIcs()[1]);
        assertTrue(fading.halfLifeBars() > 1 && fading.halfLifeBars() < 2);
        assertThrows(IllegalArgumentException.class, () -> AlphaReport.decayProfile(
                panel(), Factors.momentum(20, 0), 30, new int[]{5, 2}));
    }

    /** Synchronized zigzags with distinct amplitudes and shuffled tiny drifts. */
    private static AlphaContext zigzagPanel() {
        // Amplitudes descending; drift ranks are the permutation [4,8,2,6,1,5,3,7],
        // chosen so its Spearman correlation with the amplitude ranking is
        // EXACTLY zero (Σd² = 84 over n = 8) — the h=2 ranking (pure drift)
        // is rank-orthogonal to the h=1 ranking (amplitude).
        double[] amps = {0.08, 0.07, 0.06, 0.05, 0.04, 0.03, 0.02, 0.01};
        int[] driftRanks = {4, 8, 2, 6, 1, 5, 3, 7};
        double[] drifts = new double[8];
        for (int s = 0; s < 8; s++) {
            drifts[s] = (driftRanks[s] - 4.5) * 1e-4;
        }
        Map<String, BarSeries> data = new HashMap<>();
        for (int s = 0; s < amps.length; s++) {
            BarSeries.Builder b = BarSeries.builder("S" + s);
            for (int i = 0; i < 120; i++) {
                double close = 100 * Math.pow(1 + drifts[s], i) * (1 + amps[s] * (i % 2 == 0 ? 1 : -1));
                b.add(i, close, close * 1.0001, close * 0.9999, close, 1_000);
            }
            data.put("S" + s, b.build());
        }
        return AlphaContext.of(data);
    }

    @Test
    void attributionRecoversKnownBetasExactly() {
        // Synthetic returns with known loadings and zero noise: OLS must
        // recover alpha and betas to machine precision with R² = 1.
        int n = 100;
        java.util.Random rng = new java.util.Random(3);
        double[] f1 = new double[n];
        double[] f2 = new double[n];
        double[] y = new double[n];
        for (int t = 0; t < n; t++) {
            f1[t] = (rng.nextDouble() - 0.5) * 0.02;
            f2[t] = (rng.nextDouble() - 0.5) * 0.02;
            y[t] = 0.0002 + 1.5 * f1[t] - 0.5 * f2[t];
        }
        AlphaReport.Attribution a = AlphaReport.attribute(
                y, new double[][]{f1, f2}, List.of("MOM", "VAL"));
        assertEquals(0.0002, a.alphaPerBar(), 1e-12);
        assertEquals(1.5, a.betas()[0], 1e-9);
        assertEquals(-0.5, a.betas()[1], 1e-9);
        assertEquals(1.0, a.rSquared(), 1e-9);
        assertTrue(a.format().contains("MOM"));
        assertThrows(IllegalArgumentException.class,
                () -> AlphaReport.attribute(y, new double[][]{f1}, List.of("A", "B")));
    }

    @Test
    void curvesAndRollingMetrics() {
        double[] equity = {1.0, 1.1, 1.05, 1.2, 1.1, 1.3};
        double[] dd = AlphaReport.drawdownCurve(equity);
        assertEquals(0.0, dd[0]);
        assertEquals(1.05 / 1.1 - 1, dd[2], 1e-12);  // below the 1.1 peak
        assertEquals(0.0, dd[5], 1e-12);              // new high: no drawdown
        double[] returns = AlphaReport.returnsOf(equity);
        assertEquals(5, returns.length);
        assertEquals(0.1, returns[0], 1e-12);

        double[] rolling = AlphaReport.rollingSharpe(returns, 3, 252);
        assertTrue(Double.isNaN(rolling[0]) && Double.isNaN(rolling[1]));
        assertFalse(Double.isNaN(rolling[2]));
        // The shared ratio engine backs the summary.
        assertTrue(AlphaReport.summarize(equity, 252).sharpeRatio() > 0);
        assertThrows(IllegalArgumentException.class,
                () -> AlphaReport.rollingSharpe(returns, 1, 252));
        assertThrows(IllegalArgumentException.class,
                () -> AlphaReport.returnsOf(new double[]{1.0}));
    }
}
