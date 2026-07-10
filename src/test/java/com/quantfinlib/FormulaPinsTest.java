package com.quantfinlib;

import com.quantfinlib.core.BarSeries;
import com.quantfinlib.indicators.Indicators;
import com.quantfinlib.optimization.PortfolioOptimizer;
import com.quantfinlib.pricing.SabrModel;
import com.quantfinlib.risk.RiskMetrics;
import com.quantfinlib.simulation.MonteCarloSimulator;
import com.quantfinlib.simulation.SimulationResult;
import com.quantfinlib.volatility.EwmaVolatility;
import com.quantfinlib.volatility.Garch11;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exact hand-computed pins from the whole-suite test-quality audit:
 * every formula here previously had only BEHAVIORAL assertions
 * (positivity, monotonicity, regime reaction) — which a swapped weight,
 * flipped sign, or off-by-one exponent can survive. Each test nails one
 * value computed by hand in the comment, so the formula, not just its
 * shape, is under test.
 */
class FormulaPinsTest {

    // ------------------------------------------------------------------
    // SABR — the vol LEVEL, and the beta < 1 branch, pinned
    // ------------------------------------------------------------------

    @Test
    void sabrAtmLevelsPinnedForBetaOneAndBetaHalf() {
        // beta = 1, ATM Hagan: alpha·(1 + T·(rho·nu·alpha/4 + (2−3rho²)nu²/24))
        // = 0.2·(1 + (−0.3·0.6·0.2/4 + (2 − 0.27)·0.36/24)) = 0.2·1.016950.
        assertEquals(0.2033900, SabrModel.impliedVol(100, 100, 1.0, 0.20, 1.0, -0.30, 0.60),
                1e-7, "the absolute ATM level, not just smoothness");
        // beta = 0.5: alpha/f^0.5 · (1 + T·((1−β)²α²/(24·f) + ρβνα/(4·f^0.5)
        // + (2−3ρ²)ν²/24)) = 0.2·(1 + 4.1667e-4 − 0.0045 + 0.02595).
        assertEquals(0.2043733, SabrModel.impliedVol(100, 100, 1.0, 2.0, 0.5, -0.30, 0.60),
                1e-7, "the beta < 1 branch was previously never exercised");
    }

    // ------------------------------------------------------------------
    // EWMA — the recursion weights, pinned
    // ------------------------------------------------------------------

    @Test
    void ewmaRecursionWeightsPinned() {
        // {0.01, −0.02}: seed = sample variance 4.5e-4;
        // h[1] = 0.94·4.5e-4 + 0.06·0.01² = 4.29e-4 (a lambda swap gives 1.21e-4);
        // next = 0.94·4.29e-4 + 0.06·0.02² = 4.2726e-4 → vol 0.0206703.
        EwmaVolatility ewma = new EwmaVolatility(0.94);
        double[] h = ewma.variances(new double[]{0.01, -0.02});
        assertEquals(4.5e-4, h[0], 1e-15, "unconditional seed");
        assertEquals(4.29e-4, h[1], 1e-15, "lambda on the OLD variance, 1-lambda on r²");
        assertEquals(Math.sqrt(4.2726e-4), ewma.latestVol(new double[]{0.01, -0.02}),
                1e-12);
    }

    // ------------------------------------------------------------------
    // Sharpe / Sortino — annualization and the downside denominator
    // ------------------------------------------------------------------

    @Test
    void sharpeAndSortinoPinnedIncludingTheDownsideDenominator() {
        // {0.01, 0.03}: mean 0.02·252 = 5.04; sample std √2e-4·√252 = 0.2244994.
        assertEquals(5.04 / (Math.sqrt(2e-4) * Math.sqrt(252)),
                RiskMetrics.sharpeRatio(new double[]{0.01, 0.03}, 0, 252), 1e-9,
                "annualize the mean by 252, the vol by sqrt(252)");
        // {0.02,−0.01,0.03,−0.02}: downside dev √((1e-4+4e-4)/4) = √1.25e-4;
        // sortino = (0.005·252)/(√1.25e-4·√252) = 7.09929… — using the FULL
        // stdev by mistake yields 3.63 and fails.
        assertEquals(0.005 * 252 / (Math.sqrt(1.25e-4) * Math.sqrt(252)),
                RiskMetrics.sortinoRatio(new double[]{0.02, -0.01, 0.03, -0.02}, 0, 252),
                1e-9, "the denominator is DOWNSIDE deviation, not stdev");
    }

    // ------------------------------------------------------------------
    // RSI / ATR — Wilder smoothing and the overnight gap terms
    // ------------------------------------------------------------------

    @Test
    void rsiWilderSmoothingAndAtrGapTermsPinned() {
        // Diffs {+1, −0.5, +1}: avgGain 2/3, avgLoss 1/6 → RS 4 → RSI 80.
        // Next diff +0.5 under WILDER smoothing: avgGain (2/3·2 + 0.5)/3,
        // avgLoss (1/6·2)/3 → RS 5.5 → RSI 100 − 100/6.5 = 84.6154 (a plain
        // rolling mean gives 75 here).
        double[] rsi = Indicators.rsi(
                new double[]{100, 101, 100.5, 101.5, 102, 101, 102.5}, 3);
        assertEquals(80.0, rsi[3], 1e-9);
        assertEquals(100 - 100 / 6.5, rsi[4], 1e-9, "Wilder, not a rolling mean");

        // Bars (9,10,8,9), (9,9.5,8.5,9), (7,7.5,6.5,7): the third bar GAPS
        // down, so its true range is |low − prevClose| = 2.5, not high−low = 1.
        BarSeries.Builder b = BarSeries.builder("GAP");
        b.add(0, 9, 10, 8, 9, 100);
        b.add(1, 9, 9.5, 8.5, 9, 100);
        b.add(2, 7, 7.5, 6.5, 7, 100);
        BarSeries bars = b.build();
        double[] tr = Indicators.trueRange(bars);
        assertEquals(2.0, tr[0], 1e-12);
        assertEquals(1.0, tr[1], 1e-12);
        assertEquals(2.5, tr[2], 1e-12, "the gap term |low − prevClose|");
        double[] atr = Indicators.atr(bars, 2);
        assertEquals(1.5, atr[1], 1e-12, "seed: mean of the first two TRs");
        assertEquals(2.0, atr[2], 1e-12, "Wilder: (1.5·1 + 2.5)/2 — hl-only gives 1.25");
    }

    // ------------------------------------------------------------------
    // Tangency portfolio — the risk-free rate must matter
    // ------------------------------------------------------------------

    @Test
    void maxSharpeFindsTheAnalyticTangencyPortfolio() {
        // Diagonal cov: tangency weights ∝ (mu − rf)/sigma² = {4, 2} → {2/3, 1/3}.
        // Ignoring rf lands at {0.706, 0.294} and fails the tolerance.
        PortfolioOptimizer opt = new PortfolioOptimizer(
                new double[]{0.06, 0.10},
                new double[][]{{0.01, 0}, {0, 0.04}}, 42);
        PortfolioOptimizer.Allocation tangency = opt.maxSharpe(0.02);
        assertEquals(2.0 / 3, tangency.weights()[0], 0.03,
                "the analytic tangency, rf included");
        assertEquals(1.0 / 3, tangency.weights()[1], 0.03);
    }

    // ------------------------------------------------------------------
    // GARCH forecast — the horizon exponent, anchored at h = 1 and 2
    // ------------------------------------------------------------------

    @Test
    void garchForecastHorizonExponentAnchored() {
        Garch11.Params p = new Garch11.Params(2e-6, 0.08, 0.9, 0);
        double[] r = new double[300];
        java.util.Random rnd = new java.util.Random(3);
        for (int i = 0; i < r.length; i++) {
            r[i] = 0.01 * rnd.nextGaussian();
        }
        double mean = com.quantfinlib.util.MathUtils.mean(r);
        double[] h = Garch11.conditionalVariances(r, p);
        double lastR = r[r.length - 1] - mean;
        double oneStep = 2e-6 + 0.08 * lastR * lastR + 0.9 * h[h.length - 1];
        assertEquals(oneStep, Garch11.forecastVariance(r, p, 1), 1e-18,
                "horizon 1 IS the one-step GARCH update (persistence^0 = 1)");
        double uncond = p.unconditionalVariance();
        assertEquals(uncond + p.persistence() * (oneStep - uncond),
                Garch11.forecastVariance(r, p, 2), 1e-18,
                "horizon 2: one persistence factor, not two");
    }

    // ------------------------------------------------------------------
    // Monte Carlo — the covariance must SHAPE the distribution
    // ------------------------------------------------------------------

    @Test
    void correlationActuallyShapesThePortfolioDistribution() {
        // Two equally-weighted assets, zero drift, equal variance: at
        // rho = +0.9 the portfolio keeps ~97.5% of single-asset vol; at
        // rho = −0.9 only ~22% survives — a 4.4x risk ratio a transposed
        // Cholesky or ignored off-diagonal would destroy.
        double v = Math.pow(0.20 / Math.sqrt(252), 2);
        double[][] positive = {{v, 0.9 * v}, {0.9 * v, v}};
        double[][] negative = {{v, -0.9 * v}, {-0.9 * v, v}};
        MonteCarloSimulator mc = new MonteCarloSimulator(42);
        SimulationResult hot = mc.simulatePortfolio(1_000_000,
                new double[]{0.5, 0.5}, new double[]{0, 0}, positive, 252, 20_000);
        SimulationResult hedged = mc.simulatePortfolio(1_000_000,
                new double[]{0.5, 0.5}, new double[]{0, 0}, negative, 252, 20_000);
        assertTrue(hot.valueAtRisk(0.95) > 2.5 * hedged.valueAtRisk(0.95),
                "correlated risk vs internal hedge: " + hot.valueAtRisk(0.95)
                        + " vs " + hedged.valueAtRisk(0.95));
    }
}
