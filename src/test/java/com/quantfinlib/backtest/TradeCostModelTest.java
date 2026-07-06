package com.quantfinlib.backtest;

import com.quantfinlib.backtest.portfolio.PortfolioBacktester;
import com.quantfinlib.backtest.portfolio.PortfolioStrategy;
import com.quantfinlib.core.BarSeries;
import com.quantfinlib.data.PointInTimeUniverse;
import com.quantfinlib.microstructure.MarketImpactModel;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared cost seam: flat model reproduces the legacy commission exactly,
 * the institutional model charges size-dependent impact from the shared
 * estimator, and — the point of the whole refactor — one PortfolioBacktester
 * run can now be survivorship-aware AND execution-aware simultaneously.
 */
class TradeCostModelTest {

    private static final int BARS = 60;

    private static BarSeries series(String symbol, double drift, double volume) {
        BarSeries.Builder b = BarSeries.builder(symbol);
        java.util.Random rng = new java.util.Random(symbol.hashCode());
        double close = 100;
        for (int i = 0; i < BARS; i++) {
            double open = close;
            close = open * (1 + drift + 0.004 * (rng.nextDouble() - 0.5));
            b.add(i, open, Math.max(open, close), Math.min(open, close), close, volume);
        }
        return b.build();
    }

    private static PortfolioStrategy buyAndHold(Map<String, Double> weights) {
        return new PortfolioStrategy() {
            @Override
            public String name() {
                return "bh";
            }

            @Override
            public void init(Map<String, BarSeries> data) {
            }

            @Override
            public Map<String, Double> targetWeights(int index) {
                // Enter at bar 25 (post impact-window) and HOLD: with
                // rebalance-every-bar, an empty map would flatten the book.
                return index >= 25 ? weights : Map.of();
            }
        };
    }

    @Test
    void flatModelMatchesLegacyCommissionExactly() {
        Map<String, BarSeries> data = Map.of("X", series("X", 0.001, 1e6));
        PortfolioStrategy s = buyAndHold(Map.of("X", 1.0));
        // 10 bps commissionRate == flat(10) cost model, to the cent.
        PortfolioBacktester.Result legacy = PortfolioBacktester.run(s, data,
                new PortfolioBacktester.Config(1_000_000, 0.001, 1, 252, null));
        PortfolioBacktester.Result modeled = PortfolioBacktester.run(s, data,
                new PortfolioBacktester.Config(1_000_000, 999 /* superseded */, 1, 252,
                        TradeCostModel.flat(10)));
        assertEquals(legacy.totalCosts(), modeled.totalCosts(), 1e-9);
        assertEquals(legacy.equityCurve()[BARS - 1], modeled.equityCurve()[BARS - 1], 1e-9);
    }

    @Test
    void institutionalModelChargesSizeDependentImpact() {
        BarSeries thin = series("THIN", 0.001, 10_000); // 10k shares/day ADV
        TradeCostModel model = TradeCostModel.institutional(1, 2, 1, 20);
        // Below the impact window: flat components only.
        assertEquals(4.0, model.costBps(thin, 5, 1_000_000), 1e-12);
        // Past the window: impact appears and grows with the square root of size.
        double small = model.costBps(thin, 40, 100_000);
        double big = model.costBps(thin, 40, 10_000_000);
        assertTrue(small > 4.0);
        assertTrue(big > small);
        // sqrt law: 100x notional → ~10x the impact component.
        assertEquals((big - 4.0) / (small - 4.0), 10.0, 0.5);
        // Zero-volume series: impact unknowable, flat costs stand.
        BarSeries noVolume = series("NOVOL", 0.001, 0);
        assertEquals(4.0, model.costBps(noVolume, 40, 1_000_000), 1e-12);
        assertThrows(IllegalArgumentException.class,
                () -> TradeCostModel.institutional(-1, 2, 1, 20));
        assertThrows(IllegalArgumentException.class, () -> TradeCostModel.flat(-1));
        // The shared estimator itself validates its window.
        assertThrows(IllegalArgumentException.class,
                () -> MarketImpactModel.estimate(thin, 5, 20));
    }

    @Test
    void oneRunIsSurvivorshipAwareAndExecutionAwareTogether() {
        // The combination the review flagged as impossible: lifecycle events
        // (a delisting) AND institutional costs (impact on a thin name) in
        // the same PortfolioBacktester run.
        Map<String, BarSeries> data = Map.of(
                "ALIVE", series("ALIVE", 0.001, 50_000),
                "DOOM", series("DOOM", 0.0, 50_000));
        PointInTimeUniverse universe = new PointInTimeUniverse()
                .addMembership("ALIVE", 0)
                .addMembership("DOOM", 0)
                .recordDelisting("DOOM", 40, -1.0);
        PortfolioBacktester.Config config = PortfolioBacktester.Config.defaults()
                .withRebalanceEvery(BARS) // trade only at bar 0... strategy trades at 25
                .withCostModel(TradeCostModel.institutional(1, 2, 1, 20));
        // Rebalance every bar so the bar-25 signal executes; costs charged
        // through the institutional model on every trade.
        config = new PortfolioBacktester.Config(config.initialCapital(),
                config.commissionRate(), 1, config.periodsPerYear(), config.costModel());

        PortfolioBacktester.Result r = PortfolioBacktester.run(
                buyAndHold(Map.of("ALIVE", 0.5, "DOOM", 0.5)), data, config,
                universe, Map.of());
        // The delisting terminated half the book (survivorship-aware)...
        assertEquals(1, r.lifecycleEventsApplied());
        // ...and the entry paid institutional costs incl. impact
        // (execution-aware): more than flat 4 bps on the traded notional.
        assertTrue(r.totalCosts() > 1_000_000 * 4.0 / 1e4 * 0.9,
                "costs=" + r.totalCosts());
        // Equity reflects BOTH: the DOOM wipeout and the cost drag.
        assertTrue(r.equityCurve()[BARS - 1] < 520_000, // ~half book gone + costs
                "final=" + r.equityCurve()[BARS - 1]);
    }
}
