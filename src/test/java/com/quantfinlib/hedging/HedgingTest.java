package com.quantfinlib.hedging;

import com.quantfinlib.pricing.BlackScholes.OptionType;
import com.quantfinlib.util.MathUtils;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HedgingTest {

    // ---- Minimum-variance / beta hedging --------------------------------

    @Test
    void minimumVarianceRatioRecoversTrueExposure() {
        SplittableRandom rnd = new SplittableRandom(11);
        int n = 2_000;
        double[] hedge = new double[n];
        double[] asset = new double[n];
        for (int i = 0; i < n; i++) {
            hedge[i] = 0.01 * rnd.nextGaussian();
            asset[i] = 0.8 * hedge[i] + 0.002 * rnd.nextGaussian();
        }
        double ratio = MinimumVarianceHedge.hedgeRatio(asset, hedge);
        assertEquals(0.8, ratio, 0.03);
        assertTrue(MinimumVarianceHedge.hedgeEffectiveness(asset, hedge) > 0.9);
        // Hedging at the optimal ratio removes most variance.
        assertTrue(MinimumVarianceHedge.varianceReduction(asset, hedge, ratio) > 0.9);
        // Over-hedging is worse than optimal.
        assertTrue(MinimumVarianceHedge.varianceReduction(asset, hedge, ratio)
                > MinimumVarianceHedge.varianceReduction(asset, hedge, 2 * ratio));
    }

    @Test
    void futuresContractSizing() {
        // Hull-style example: beta 1.2, $10M portfolio, futures at 5000 x $50.
        assertEquals(-48, MinimumVarianceHedge.fullHedgeContracts(1.2, 10_000_000, 5_000, 50), 1e-9);
        // Halve the beta instead of removing it.
        assertEquals(-24, MinimumVarianceHedge.betaAdjustmentContracts(1.2, 0.6, 10_000_000, 5_000, 50), 1e-9);
    }

    // ---- Delta hedging ---------------------------------------------------

    private static double[] gbmPath(double s0, double drift, double vol, int steps,
                                    double dt, SplittableRandom rnd) {
        double[] path = new double[steps + 1];
        path[0] = s0;
        for (int i = 1; i <= steps; i++) {
            path[i] = path[i - 1] * Math.exp((drift - 0.5 * vol * vol) * dt
                    + vol * Math.sqrt(dt) * rnd.nextGaussian());
        }
        return path;
    }

    @Test
    void deltaHedgingCollapsesShortOptionRisk() {
        double vol = 0.2, r = 0.05, t = 0.5, dt = 1.0 / 252;
        int steps = 126, paths = 200;
        SplittableRandom rnd = new SplittableRandom(42);

        double[] hedged = new double[paths];
        double[] naked = new double[paths];
        double premium = 0;
        for (int p = 0; p < paths; p++) {
            double[] path = gbmPath(100, r, vol, steps, dt, rnd);
            DeltaHedger.HedgeReport report = DeltaHedger.simulateShortOption(
                    OptionType.CALL, 100, t, r, 0, vol, path, dt, DeltaHedger.Config.every(0));
            hedged[p] = report.finalPnl();
            naked[p] = report.premium() * Math.exp(r * t) - report.payoff();
            premium = report.premium();
        }
        double hedgedStd = MathUtils.stdDev(hedged);
        double nakedStd = MathUtils.stdDev(naked);

        // Daily rebalancing removes the vast majority of the short option's risk.
        assertTrue(hedgedStd < 0.35 * nakedStd,
                "hedged std " + hedgedStd + " vs naked " + nakedStd);
        // Replication error is small relative to the premium and centered near zero.
        assertTrue(hedgedStd < 0.25 * premium);
        assertTrue(Math.abs(MathUtils.mean(hedged)) < 0.15 * premium);
    }

    @Test
    void widerBandsTradeLessButHedgeWorse() {
        double vol = 0.25, r = 0.02, t = 0.5, dt = 1.0 / 252;
        SplittableRandom rnd = new SplittableRandom(7);
        int paths = 150;

        double[] tight = new double[paths];
        double[] wide = new double[paths];
        int tightTrades = 0, wideTrades = 0;
        for (int p = 0; p < paths; p++) {
            double[] path = gbmPath(100, r, vol, 126, dt, rnd);
            DeltaHedger.HedgeReport a = DeltaHedger.simulateShortOption(
                    OptionType.CALL, 100, t, r, 0, vol, path, dt, new DeltaHedger.Config(0.01, 1));
            DeltaHedger.HedgeReport b = DeltaHedger.simulateShortOption(
                    OptionType.CALL, 100, t, r, 0, vol, path, dt, new DeltaHedger.Config(0.25, 1));
            tight[p] = a.finalPnl();
            wide[p] = b.finalPnl();
            tightTrades += a.rebalances();
            wideTrades += b.rebalances();
        }
        assertTrue(tightTrades > 3 * wideTrades, tightTrades + " vs " + wideTrades);
        assertTrue(MathUtils.stdDev(tight) < MathUtils.stdDev(wide),
                "tight " + MathUtils.stdDev(tight) + " wide " + MathUtils.stdDev(wide));
    }

    @Test
    void transactionCostsShowUpInTheReport() {
        double[] path = gbmPath(100, 0.05, 0.2, 126, 1.0 / 252, new SplittableRandom(3));
        DeltaHedger.HedgeReport free = DeltaHedger.simulateShortOption(
                OptionType.CALL, 100, 0.5, 0.05, 0, 0.2, path, 1.0 / 252, DeltaHedger.Config.every(0));
        DeltaHedger.HedgeReport costly = DeltaHedger.simulateShortOption(
                OptionType.CALL, 100, 0.5, 0.05, 0, 0.2, path, 1.0 / 252, DeltaHedger.Config.every(10));
        assertEquals(0, free.tradingCosts(), 1e-12);
        assertTrue(costly.tradingCosts() > 0);
        assertTrue(costly.finalPnl() < free.finalPnl());   // same path, costs only hurt
        assertEquals(free.turnover(), costly.turnover(), 1e-9);
    }

    // ---- Greek neutralization ---------------------------------------------

    @Test
    void deltaGammaHedgeZeroesBothGreeks() {
        // Portfolio: delta +500, gamma -30. Hedge option: delta 0.5, gamma 0.02.
        double[] q = GreekHedger.deltaGammaHedge(500, -30, 0.5, 0.02);
        assertEquals(1500, q[1], 1e-9);                     // options: +30/0.02
        assertEquals(-(500 + 1500 * 0.5), q[0], 1e-9);      // underlying: -1250
        // Verify the residuals are exactly zero.
        assertEquals(0, 500 + q[0] * 1 + q[1] * 0.5, 1e-9);
        assertEquals(0, -30 + q[1] * 0.02, 1e-9);
    }

    @Test
    void deltaGammaVegaHedgeNeutralizesAllThree() {
        GreekHedger.Instrument opt1 = new GreekHedger.Instrument("OPT1", 0.55, 0.020, 0.30);
        GreekHedger.Instrument opt2 = new GreekHedger.Instrument("OPT2", 0.35, 0.015, 0.45);
        double[] portfolio = {1_200, -80, 250};

        double[] q = GreekHedger.deltaGammaVegaHedge(portfolio[0], portfolio[1], portfolio[2], opt1, opt2);
        double[] residual = GreekHedger.residualGreeks(portfolio,
                new GreekHedger.Instrument[]{GreekHedger.Instrument.underlying("SPOT"), opt1, opt2}, q);
        for (double r : residual) {
            assertEquals(0, r, 1e-9);
        }
    }

    // ---- FX hedging -----------------------------------------------------------

    @Test
    void fxNettingAndOptimalRatio() {
        Map<String, Double> net = FxHedger.netExposures(List.of(
                new FxHedger.FxExposure("EUR", 5_000_000),
                new FxHedger.FxExposure("EUR", -2_000_000),
                new FxHedger.FxExposure("JPY", 1_000_000)));
        assertEquals(3_000_000, net.get("EUR"), 1e-9);

        // Fully exposed foreign asset: unhedged return = local + fx -> h* ≈ 1.
        SplittableRandom rnd = new SplittableRandom(5);
        int n = 2_000;
        double[] fx = new double[n];
        double[] unhedged = new double[n];
        for (int i = 0; i < n; i++) {
            fx[i] = 0.006 * rnd.nextGaussian();
            unhedged[i] = 0.004 * rnd.nextGaussian() + fx[i];
        }
        double h = FxHedger.optimalHedgeRatio(unhedged, fx);
        assertEquals(1.0, h, 0.05);
        assertTrue(MathUtils.stdDev(FxHedger.hedgedReturns(unhedged, fx, h))
                < MathUtils.stdDev(unhedged));
        assertEquals(3_000_000, FxHedger.hedgeNotional(net.get("EUR"), 1.0), 1e-9);
    }

    @Test
    void forwardCarryFromForwardPoints() {
        // Spot 1.1000, 1y forward 1.1110: hedging costs ~100bps of carry.
        assertEquals(100, FxHedger.forwardCarryBps(1.1000, 1.1110, 1.0), 0.01);
        // 6m tenor annualizes: same points over half the time = double the rate.
        assertEquals(200, FxHedger.forwardCarryBps(1.1000, 1.1110, 0.5), 0.01);
    }

    // ---- Pairs hedging ----------------------------------------------------------

    @Test
    void pairsAnalysisRecoversRatioAndHalfLife() {
        SplittableRandom rnd = new SplittableRandom(17);
        int n = 1_500;
        double[] b = new double[n];
        double[] a = new double[n];
        b[0] = 100;
        double ou = 0;
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                b[i] = b[i - 1] + rnd.nextGaussian();
            }
            ou = 0.9 * ou + 0.5 * rnd.nextGaussian();    // AR(1): half-life ≈ 6.6 bars
            a[i] = 10 + 1.5 * b[i] + ou;
        }
        PairsHedger.PairsAnalysis analysis = PairsHedger.analyze(a, b);
        assertEquals(1.5, analysis.hedgeRatio(), 0.05);
        assertEquals(10, analysis.intercept(), 5.0);
        assertTrue(analysis.correlation() > 0.7);
        assertTrue(analysis.halfLifeBars() > 3 && analysis.halfLifeBars() < 15,
                "halfLife=" + analysis.halfLifeBars());
        assertTrue(Math.abs(analysis.lastZScore()) < 5);
        // The hedged spread is far less volatile than the raw leg.
        assertTrue(MathUtils.stdDev(analysis.spread()) < 0.1 * MathUtils.stdDev(a));
    }

    @Test
    void trendingSpreadHasNoMeanReversion() {
        double[] trending = new double[100];
        for (int i = 0; i < 100; i++) {
            trending[i] = i * 1.0;
        }
        assertEquals(Double.POSITIVE_INFINITY, PairsHedger.halfLife(trending));
    }
}
