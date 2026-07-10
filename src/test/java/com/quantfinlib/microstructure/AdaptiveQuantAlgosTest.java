package com.quantfinlib.microstructure;

import com.quantfinlib.execution.Ucb1Selector;
import com.quantfinlib.hedging.WhalleyWilmott;
import com.quantfinlib.risk.RiskMetrics;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Variance ratio, Kalman beta, Whalley-Wilmott bands, UCB1 selection. */
class AdaptiveQuantAlgosTest {

    // ------------------------------------------------------------------
    // Variance ratio — which regime IS this series?
    // ------------------------------------------------------------------

    @Test
    void varianceRatioSeparatesTheThreeRegimes() {
        Random rnd = new Random(42);
        int n = 2_000;

        // A random walk reads 1, and the z-stat says "noise".
        double[] rw = new double[n];
        for (int i = 0; i < n; i++) {
            rw[i] = 0.01 * rnd.nextGaussian();
        }
        VarianceRatio.Result walk = VarianceRatio.test(rw, 2);
        assertEquals(1.0, walk.ratio(), 0.1, "linear variance growth: " + walk.ratio());
        // z ~ N(0,1) under the null: bound at 3 sigma, not 2 — a true
        // random walk draws |z| in [2, 3) about 4.5% of the time, and a
        // seed-sensitive assertion at the decision boundary is a flake,
        // not a test. The DECISIVE rejections below pin the boundary.
        assertTrue(Math.abs(walk.zStat()) < 3, "z = " + walk.zStat());

        // Mean reversion cancels across horizons: VR(2) ≈ 1 + ρ₁ < 1.
        double[] reverting = new double[n];
        double prev = 0;
        for (int i = 0; i < n; i++) {
            reverting[i] = -0.4 * prev + 0.01 * rnd.nextGaussian();
            prev = reverting[i];
        }
        VarianceRatio.Result rev = VarianceRatio.test(reverting, 2);
        assertTrue(rev.ratio() < 0.8, "cancellation across horizons: " + rev.ratio());
        assertTrue(rev.zStat() < -2 && rev.rejectsRandomWalk());

        // Momentum compounds: VR > 1.
        double[] trending = new double[n];
        prev = 0;
        for (int i = 0; i < n; i++) {
            trending[i] = 0.4 * prev + 0.01 * rnd.nextGaussian();
            prev = trending[i];
        }
        VarianceRatio.Result mom = VarianceRatio.test(trending, 2);
        assertTrue(mom.ratio() > 1.2 && mom.zStat() > 2,
                "compounding across horizons: " + mom.ratio());

        assertThrows(IllegalArgumentException.class, () -> VarianceRatio.test(rw, 1));
        assertThrows(IllegalArgumentException.class,
                () -> VarianceRatio.test(new double[30], 5), "too short");
        assertThrows(IllegalArgumentException.class,
                () -> VarianceRatio.test(new double[100], 2), "no variance");
    }

    // ------------------------------------------------------------------
    // Kalman beta — tracking what a static OLS averages away
    // ------------------------------------------------------------------

    @Test
    void kalmanTracksADriftingBetaThatStaticOlsAveragesAway() {
        Random rnd = new Random(7);
        int n = 3_000;
        double[] x = new double[n];
        double[] y = new double[n];
        KalmanBeta filter = new KalmanBeta(1.0, 1.0, 1e-7, 1e-6);
        for (int i = 0; i < n; i++) {
            double trueBeta = 1.0 + i / (double) (n - 1);   // drifts 1 -> 2
            x[i] = 0.02 * rnd.nextGaussian();
            y[i] = trueBeta * x[i] + 0.001 * rnd.nextGaussian();
            filter.onObservation(x[i], y[i]);
        }
        assertEquals(2.0, filter.beta(), 0.15,
                "the filter is AT the current beta: " + filter.beta());
        double ols = RiskMetrics.beta(y, x);
        assertTrue(ols > 1.3 && ols < 1.7,
                "static OLS averages the drift into ~1.5: " + ols
                        + " — exactly the stale hedge ratio the filter exists to avoid");
        assertTrue(filter.betaVariance() < 0.01,
                "and the filter KNOWS how sure it is: " + filter.betaVariance());

        // processNoise = 0 is recursive least squares: converge and hold.
        KalmanBeta rls = new KalmanBeta(0, 1.0, 0, 1e-6);
        for (int i = 0; i < 2_000; i++) {
            double xi = 0.02 * rnd.nextGaussian();
            rls.onObservation(xi, 1.5 * xi + 0.001 * rnd.nextGaussian());
        }
        assertEquals(1.5, rls.beta(), 0.05, "constant relationship: converged");
        assertTrue(rls.betaVariance() < 1.0 / 100, "certainty grows with evidence");

        assertThrows(IllegalArgumentException.class,
                () -> new KalmanBeta(1, 0, 1e-7, 1e-6));
        assertThrows(IllegalArgumentException.class,
                () -> filter.onObservation(Double.NaN, 1));
    }

    // ------------------------------------------------------------------
    // Whalley-Wilmott — the optimal band, and the hedge-to-edge policy
    // ------------------------------------------------------------------

    @Test
    void whalleyWilmottBandScalesByCubeRootsAndHedgesToTheEdge() {
        // The pin: cbrt(1.5 · 0.0005 · 100 · 0.02²) = cbrt(3e-5).
        double band = WhalleyWilmott.bandHalfWidth(100, 0.02, 0.0005, 1);
        assertEquals(0.0310723, band, 1e-6, "the asymptotic optimum, by hand");
        // The cube-root laws, exactly: costs x8 -> band x2; lambda x8 -> /2.
        assertEquals(2 * band, WhalleyWilmott.bandHalfWidth(100, 0.02, 0.004, 1), 1e-12);
        assertEquals(band / 2, WhalleyWilmott.bandHalfWidth(100, 0.02, 0.0005, 8), 1e-12);

        // The POLICY: hold inside; outside, trade to the NEAREST EDGE —
        // hedging to delta itself would pay the spread again tomorrow.
        assertFalse(WhalleyWilmott.rebalance(0.48, 0.50, 0.05).trade(), "inside: hold");
        WhalleyWilmott.Action low = WhalleyWilmott.rebalance(0.40, 0.50, 0.05);
        assertTrue(low.trade());
        assertEquals(0.45, low.targetHedge(), 1e-12, "to the edge, NOT the center");
        WhalleyWilmott.Action high = WhalleyWilmott.rebalance(0.60, 0.50, 0.05);
        assertEquals(0.55, high.targetHedge(), 1e-12);

        // Zero gamma degenerates honestly: zero band, hedge exactly to delta.
        assertEquals(0, WhalleyWilmott.bandHalfWidth(100, 0, 0.0005, 1), 0.0);
        assertEquals(0.50, WhalleyWilmott.rebalance(0.40, 0.50, 0).targetHedge(), 1e-12);

        assertThrows(IllegalArgumentException.class,
                () -> WhalleyWilmott.bandHalfWidth(100, 0.02, 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> WhalleyWilmott.rebalance(Double.NaN, 0.5, 0.05));
    }

    // ------------------------------------------------------------------
    // UCB1 — exploration that decays exactly as evidence accumulates
    // ------------------------------------------------------------------

    @Test
    void ucb1FindsTheBestArmWithoutAbandoningTheOthers() {
        double[] armRewards = {0.3, 0.5, 0.8};        // deterministic venues
        Ucb1Selector bandit = new Ucb1Selector(3);
        // Every arm earns one look first, in index order.
        for (int expected = 0; expected < 3; expected++) {
            int arm = bandit.select();
            assertEquals(expected, arm);
            bandit.record(arm, armRewards[arm]);
        }
        for (int round = 3; round < 1_000; round++) {
            int arm = bandit.select();
            bandit.record(arm, armRewards[arm]);
        }
        assertEquals(1_000, bandit.totalPulls());
        assertTrue(bandit.pulls(2) > 700, "the best venue carries the flow: "
                + bandit.pulls(2));
        assertTrue(bandit.pulls(0) >= 1 && bandit.pulls(1) >= 1,
                "but no venue is ever fully abandoned — that is the guarantee");
        assertEquals(0.8, bandit.meanReward(2), 1e-12, "deterministic reward, exact mean");
        double realized = (0.3 * bandit.pulls(0) + 0.5 * bandit.pulls(1)
                + 0.8 * bandit.pulls(2)) / 1_000;
        assertTrue(realized > 0.75, "low regret: " + realized);

        assertThrows(IllegalArgumentException.class, () -> new Ucb1Selector(1));
        assertThrows(IllegalArgumentException.class, () -> bandit.record(0, 1.5),
                "a mis-scaled reward silently breaks exploration: gated");
        assertThrows(IllegalArgumentException.class, () -> bandit.record(9, 0.5));
        assertTrue(Double.isNaN(new Ucb1Selector(2).meanReward(0)));
    }
}
