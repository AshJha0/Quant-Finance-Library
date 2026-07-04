package com.quantfinlib.backtest.portfolio;

import com.quantfinlib.TestData;
import com.quantfinlib.core.BarSeries;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortfolioBacktestTest {

    private static PortfolioStrategy constantWeights(Map<String, Double> weights) {
        return new PortfolioStrategy() {
            @Override
            public String name() {
                return "CONSTANT";
            }

            @Override
            public void init(Map<String, BarSeries> data) {
            }

            @Override
            public Map<String, Double> targetWeights(int index) {
                return weights;
            }
        };
    }

    private static Map<String, BarSeries> twoAssets() {
        return Map.of(
                "UP", TestData.gbmSeries("UP", 400, 100, 0.15, 0.15, 1),
                "DOWN", TestData.gbmSeries("DOWN", 400, 100, -0.20, 0.20, 2));
    }

    @Test
    void constantMixTracksTargetWeights() {
        PortfolioBacktester.Result r = PortfolioBacktester.run(
                constantWeights(Map.of("UP", 0.6, "DOWN", 0.4)),
                twoAssets(), PortfolioBacktester.Config.defaults());

        assertEquals(400, r.equityCurve().length);
        assertTrue(r.equityCurve()[399] > 0);
        assertTrue(r.totalCosts() > 0);
        assertEquals(2, r.finalPositions().size());
        assertTrue(r.finalPositions().get("UP") > 0);
    }

    @Test
    void shortingTheFallingAssetProfits() {
        // Deterministic decline from 100 to ~40 so the direction is guaranteed.
        Map<String, BarSeries> data = Map.of(
                "DOWN", BarSeries.of("DOWN", TestData.sineTrend(400, 100, -0.15, 2, 40)));
        PortfolioBacktester.Config noCosts = new PortfolioBacktester.Config(1_000_000, 0, 1, 252);

        PortfolioBacktester.Result longDown = PortfolioBacktester.run(
                constantWeights(Map.of("DOWN", 1.0)), data, noCosts);
        PortfolioBacktester.Result shortDown = PortfolioBacktester.run(
                constantWeights(Map.of("DOWN", -1.0)), data, noCosts);

        assertTrue(longDown.metrics().totalReturn() < 0);
        assertTrue(shortDown.metrics().totalReturn() > 0);
        assertTrue(shortDown.finalPositions().get("DOWN") < 0);
    }

    @Test
    void lessFrequentRebalancingCostsLess() {
        Map<String, BarSeries> data = twoAssets();
        Map<String, Double> weights = Map.of("UP", 0.5, "DOWN", 0.5);
        PortfolioBacktester.Result daily = PortfolioBacktester.run(
                constantWeights(weights), data, PortfolioBacktester.Config.defaults());
        PortfolioBacktester.Result monthly = PortfolioBacktester.run(
                constantWeights(weights), data,
                PortfolioBacktester.Config.defaults().withRebalanceEvery(21));

        assertTrue(monthly.totalCosts() < daily.totalCosts());
        assertTrue(monthly.totalTurnoverNotional() < daily.totalTurnoverNotional());
    }

    @Test
    void positionSizingRules() {
        assertEquals(2.0, PositionSizing.kellyFraction(0.02, 0.01), 1e-12);   // mu/sigma^2
        assertEquals(1.0, PositionSizing.halfKelly(0.02, 0.01), 1e-12);
        // Risk 1% of 100k with a $2 stop distance: 500 shares.
        assertEquals(500, PositionSizing.fixedFractionalQuantity(100_000, 0.01, 50, 48), 1e-9);
        // Inverse vol: vols 0.1 and 0.3 -> weights 0.75 / 0.25.
        double[] w = PositionSizing.inverseVolatilityWeights(new double[]{0.1, 0.3});
        assertEquals(0.75, w[0], 1e-12);
        assertEquals(0.25, w[1], 1e-12);
        assertEquals(0.5, PositionSizing.volatilityTargetLeverage(0.20, 0.10), 1e-12);
    }
}
