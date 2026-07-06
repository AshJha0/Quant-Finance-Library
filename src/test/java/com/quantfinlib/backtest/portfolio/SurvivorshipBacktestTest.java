package com.quantfinlib.backtest.portfolio;

import com.quantfinlib.core.BarSeries;
import com.quantfinlib.data.CorporateActions;
import com.quantfinlib.data.PointInTimeUniverse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The survivorship-aware backtester: delistings cost the full loss (and the
 * naive run demonstrably overstates equity — the bias itself, quantified),
 * index drops force liquidation, mergers convert at deal terms, and cash
 * dividends credit holders / debit shorts on the ex-date.
 */
class SurvivorshipBacktestTest {

    private static final int BARS = 10;

    /** Flat-price series with timestamps 0..n-1 (the test's clock). */
    private static BarSeries flat(String symbol, double price) {
        BarSeries.Builder b = BarSeries.builder(symbol);
        for (int i = 0; i < BARS; i++) {
            b.add(i, price, price * 1.001, price * 0.999, price, 1_000);
        }
        return b.build();
    }

    /** Buys the given weights at bar 0 and holds (no further rebalance). */
    private static PortfolioStrategy buyAndHold(Map<String, Double> weights) {
        return new PortfolioStrategy() {
            @Override
            public String name() {
                return "buy-and-hold";
            }

            @Override
            public void init(Map<String, BarSeries> data) {
            }

            @Override
            public Map<String, Double> targetWeights(int index) {
                return index == 0 ? weights : Map.of();
            }
        };
    }

    /** Rebalance once at bar 0 only; zero commission so arithmetic is exact. */
    private static PortfolioBacktester.Config config() {
        return new PortfolioBacktester.Config(1_000_000, 0, BARS, 252, null);
    }

    @Test
    void delistingCostsTheFullLossAndTheNaiveRunHidesIt() {
        Map<String, BarSeries> data = Map.of(
                "ALIVE", flat("ALIVE", 100),
                // A forward-filled ghost: still printing 50 after death — the
                // exact data shape unionForwardFill produces for a dead name.
                "DOOM", flat("DOOM", 50));
        PortfolioStrategy strategy = buyAndHold(Map.of("ALIVE", 0.5, "DOOM", 0.5));

        // Bankruptcy at t=5, shareholders wiped out.
        PointInTimeUniverse universe = new PointInTimeUniverse()
                .addMembership("ALIVE", 0)
                .addMembership("DOOM", 0)
                .recordDelisting("DOOM", 5, -1.0);

        PortfolioBacktester.Result unbiased = PortfolioBacktester.run(
                strategy, data, config(), universe, Map.of());
        PortfolioBacktester.Result naive = PortfolioBacktester.run(strategy, data, config());

        // Naive: flat prices, no loss — the survivorship illusion.
        assertEquals(1_000_000, naive.equityCurve()[BARS - 1], 1e-6);
        // Unbiased: the DOOM half is gone; equity ends at 500k.
        assertEquals(500_000, unbiased.equityCurve()[BARS - 1], 1e-6);
        // The bias, quantified: the naive run overstates final equity by 2x.
        assertTrue(naive.equityCurve()[BARS - 1] > unbiased.equityCurve()[BARS - 1]);
        assertEquals(1, unbiased.lifecycleEventsApplied());
        // The dead position is truly gone, not forward-filled.
        assertFalse(unbiased.finalPositions().containsKey("DOOM"));
        // A partial-recovery delisting (Shumway haircut) lands in between.
        PointInTimeUniverse haircut = new PointInTimeUniverse()
                .addMembership("ALIVE", 0)
                .addMembership("DOOM", 0)
                .recordDelisting("DOOM", 5,
                        PointInTimeUniverse.DEFAULT_INVOLUNTARY_DELISTING_RETURN);
        double withHaircut = PortfolioBacktester.run(
                strategy, data, config(), haircut, Map.of()).equityCurve()[BARS - 1];
        assertEquals(1_000_000 - 500_000 * 0.30, withHaircut, 1e-6);
    }

    @Test
    void indexDropForcesLiquidationAtTheDropBar() {
        Map<String, BarSeries> data = Map.of("DROP", flat("DROP", 100));
        PortfolioStrategy strategy = buyAndHold(Map.of("DROP", 1.0));
        // Member through t=3; drops (still listed — no terminal event).
        PointInTimeUniverse universe = new PointInTimeUniverse()
                .addMembership("DROP", 0, 3);

        PortfolioBacktester.Result r = PortfolioBacktester.run(
                strategy, data, config(), universe, Map.of());
        // Sold at the t=4 close: flat prices → full capital back, no position.
        assertEquals(1_000_000, r.equityCurve()[BARS - 1], 1e-6);
        assertTrue(r.finalPositions().isEmpty());
        assertEquals(0, r.lifecycleEventsApplied()); // a drop is not a death
        // Turnover shows both the entry and the forced exit.
        assertEquals(2_000_000, r.totalTurnoverNotional(), 1e-6);
    }

    @Test
    void mergerConvertsPositionsAtDealTerms() {
        Map<String, BarSeries> data = Map.of(
                "TGT", flat("TGT", 40),
                "ACQ", flat("ACQ", 100));
        PortfolioStrategy strategy = buyAndHold(Map.of("TGT", 1.0));
        // Each TGT share becomes $10 cash + 0.5 ACQ shares at t=5.
        PointInTimeUniverse universe = new PointInTimeUniverse()
                .addMembership("TGT", 0)
                .addMembership("ACQ", 0)
                .recordMerger("TGT", 5, 10, 0.5, "ACQ");

        PortfolioBacktester.Result r = PortfolioBacktester.run(
                strategy, data, config(), universe, Map.of());
        double tgtShares = 1_000_000.0 / 40;                    // 25k shares
        assertFalse(r.finalPositions().containsKey("TGT"));
        assertEquals(tgtShares * 0.5, r.finalPositions().get("ACQ"), 1e-6);
        // Final equity: cash component + acquirer stake at 100.
        assertEquals(tgtShares * 10 + tgtShares * 0.5 * 100,
                r.equityCurve()[BARS - 1], 1e-6);
        assertEquals(1, r.lifecycleEventsApplied());

        // A stock-paying merger whose acquirer is not in the backtest must
        // fail loudly, not silently vaporize the position.
        PointInTimeUniverse badDeal = new PointInTimeUniverse()
                .addMembership("TGT", 0)
                .recordMerger("TGT", 5, 0, 1.0, "MISSING");
        assertThrows(IllegalArgumentException.class, () -> PortfolioBacktester.run(
                strategy, Map.of("TGT", flat("TGT", 40)), config(), badDeal, Map.of()));
    }

    @Test
    void dividendsCreditHoldersAndDebitShortsOnTheExDate() {
        Map<String, BarSeries> data = Map.of("DIV", flat("DIV", 100));
        List<CorporateActions.CorporateAction> divs = List.of(
                new CorporateActions.CorporateAction(3, CorporateActions.Type.CASH_DIVIDEND, 2.0),
                // Ex-date before entry: nobody holds it, no cash moves.
                new CorporateActions.CorporateAction(0, CorporateActions.Type.CASH_DIVIDEND, 5.0));

        PortfolioBacktester.Result longRun = PortfolioBacktester.run(
                buyAndHold(Map.of("DIV", 1.0)), data, config(), null, Map.of("DIV", divs));
        double shares = 1_000_000.0 / 100;
        // $2/share on 10k shares (the t=0 dividend paid to a flat book: 0).
        assertEquals(shares * 2, longRun.dividendCashCredited(), 1e-6);
        assertEquals(1_000_000 + shares * 2, longRun.equityCurve()[BARS - 1], 1e-6);

        // The short pays the dividend — the cost adjusted prices can't show.
        PortfolioBacktester.Result shortRun = PortfolioBacktester.run(
                buyAndHold(Map.of("DIV", -1.0)), data, config(), null, Map.of("DIV", divs));
        assertEquals(-shares * 2, shortRun.dividendCashCredited(), 1e-6);
        assertEquals(1_000_000 - shares * 2, shortRun.equityCurve()[BARS - 1], 1e-6);
    }

    @Test
    void sameBarMergerIntoDyingAcquirerIsDeterministic() {
        // TGT merges into ACQ and ACQ delists with the SAME effective bar:
        // mergers process before delistings, so the converted shares settle
        // at the acquirer's terms — independent of map iteration order.
        Map<String, BarSeries> data = Map.of(
                "TGT", flat("TGT", 40),
                "ACQ", flat("ACQ", 100));
        PointInTimeUniverse universe = new PointInTimeUniverse()
                .addMembership("TGT", 0)
                .addMembership("ACQ", 0)
                .recordMerger("TGT", 5, 10, 0.5, "ACQ")
                .recordDelisting("ACQ", 5, 0.0); // orderly exit at last close
        PortfolioBacktester.Result r = PortfolioBacktester.run(
                buyAndHold(Map.of("TGT", 1.0)), data, config(), universe, Map.of());
        double tgtShares = 1_000_000.0 / 40;
        // Cash leg + converted shares paid out at ACQ's last close (100).
        assertEquals(tgtShares * 10 + tgtShares * 0.5 * 100,
                r.equityCurve()[BARS - 1], 1e-6);
        assertEquals(2, r.lifecycleEventsApplied());
        assertTrue(r.finalPositions().isEmpty());
    }

    @Test
    void dividendOnTheDelistingBarStillPaysTheHolder() {
        // The ex-date entitlement belongs to whoever held through the prior
        // close — even when the name delists on its own ex-date. Dividends
        // are processed before same-bar lifecycle events for exactly this.
        Map<String, BarSeries> data = Map.of("DOOM", flat("DOOM", 50));
        PointInTimeUniverse universe = new PointInTimeUniverse()
                .addMembership("DOOM", 0)
                .recordDelisting("DOOM", 5, -1.0);
        List<CorporateActions.CorporateAction> divs = List.of(
                new CorporateActions.CorporateAction(5, CorporateActions.Type.CASH_DIVIDEND, 2.0));
        PortfolioBacktester.Result r = PortfolioBacktester.run(
                buyAndHold(Map.of("DOOM", 1.0)), data, config(), universe, Map.of("DOOM", divs));
        double shares = 1_000_000.0 / 50;
        // The dividend lands; the position is then wiped by the delisting.
        assertEquals(shares * 2, r.dividendCashCredited(), 1e-6);
        assertEquals(shares * 2, r.equityCurve()[BARS - 1], 1e-6);
        assertEquals(1, r.lifecycleEventsApplied());
    }

    @Test
    void classicOverloadIsUnchanged() {
        Map<String, BarSeries> data = Map.of("X", flat("X", 100));
        PortfolioBacktester.Result r = PortfolioBacktester.run(
                buyAndHold(Map.of("X", 1.0)), data, config());
        assertEquals(0, r.dividendCashCredited());
        assertEquals(0, r.lifecycleEventsApplied());
        assertEquals(1_000_000, r.equityCurve()[BARS - 1], 1e-6);
    }
}
