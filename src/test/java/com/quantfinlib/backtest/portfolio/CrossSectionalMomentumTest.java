package com.quantfinlib.backtest.portfolio;

import com.quantfinlib.core.BarSeries;
import com.quantfinlib.data.PointInTimeUniverse;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Point-in-time cross-sectional momentum: ranks only the members alive at
 * each rebalance timestamp, longs winners / shorts losers dollar-neutrally,
 * and — run end-to-end on the survivorship-aware engine — shows the naive
 * universe overstating the strategy.
 */
class CrossSectionalMomentumTest {

    private static final int BARS = 30;

    /** Geometric drift series: close = 100 · (1 + drift)^i, timestamps 0..n-1. */
    private static BarSeries drift(String symbol, double perBar) {
        BarSeries.Builder b = BarSeries.builder(symbol);
        double close = 100;
        for (int i = 0; i < BARS; i++) {
            double open = close;
            close = 100 * Math.pow(1 + perBar, i + 1);
            b.add(i, open, Math.max(open, close), Math.min(open, close), close, 1_000);
        }
        return b.build();
    }

    private static Map<String, BarSeries> market() {
        Map<String, BarSeries> data = new HashMap<>();
        data.put("WIN", drift("WIN", 0.010));    // best trailing return
        data.put("MEH", drift("MEH", 0.001));
        data.put("BLAH", drift("BLAH", 0.000));
        data.put("LOSE", drift("LOSE", -0.010)); // worst trailing return
        return data;
    }

    private static PointInTimeUniverse allMembers() {
        PointInTimeUniverse u = new PointInTimeUniverse();
        for (String s : new String[]{"WIN", "MEH", "BLAH", "LOSE"}) {
            u.addMembership(s, 0);
        }
        return u;
    }

    @Test
    void ranksMembersAndBuildsADollarNeutralBook() {
        CrossSectionalMomentum strategy = new CrossSectionalMomentum(allMembers(),
                new CrossSectionalMomentum.Config(10, 2, 1, 0.5));
        strategy.init(market());
        // Before the lookback is available: hold cash.
        assertTrue(strategy.targetWeights(5).isEmpty());
        Map<String, Double> w = strategy.targetWeights(15);
        assertEquals(0.5, w.get("WIN"), 1e-12);   // long the winner
        assertEquals(-0.5, w.get("LOSE"), 1e-12); // short the loser
        assertEquals(2, w.size());                // middle names stay flat
        // Dollar-neutral by construction.
        assertEquals(0, w.values().stream().mapToDouble(Double::doubleValue).sum(), 1e-12);
    }

    @Test
    void deadStocksNeverEnterTheCrossSection() {
        // LOSE delists at t=12: the short book's favorite name disappears —
        // exactly the case survivorship bias papers over.
        PointInTimeUniverse universe = new PointInTimeUniverse()
                .addMembership("WIN", 0)
                .addMembership("MEH", 0)
                .addMembership("BLAH", 0)
                .addMembership("LOSE", 0)
                .recordDelisting("LOSE", 12, -1.0);
        CrossSectionalMomentum strategy = new CrossSectionalMomentum(universe,
                new CrossSectionalMomentum.Config(10, 2, 1, 0.5));
        strategy.init(market());
        // Before the delisting LOSE is shorted...
        assertEquals(-0.5, strategy.targetWeights(11).get("LOSE"), 1e-12);
        // ...after it, the ranking re-forms over the survivors only.
        Map<String, Double> after = strategy.targetWeights(15);
        assertFalse(after.containsKey("LOSE"));
        assertEquals(-0.5, after.get("BLAH"), 1e-12); // next-worst becomes the short
        assertEquals(0.5, after.get("WIN"), 1e-12);
    }

    @Test
    void sideSizeShrinksWhenMembersAreScarce() {
        // Two members can fill one name per side; perSide=3 must shrink, not
        // overlap long and short books.
        PointInTimeUniverse universe = new PointInTimeUniverse()
                .addMembership("WIN", 0)
                .addMembership("LOSE", 0);
        CrossSectionalMomentum strategy = new CrossSectionalMomentum(universe,
                new CrossSectionalMomentum.Config(10, 0, 3, 0.6));
        strategy.init(market());
        Map<String, Double> w = strategy.targetWeights(15);
        assertEquals(2, w.size());
        assertEquals(0.6, w.get("WIN"), 1e-12);
        assertEquals(-0.6, w.get("LOSE"), 1e-12);
        // One member: no pairable cross-section, hold cash.
        PointInTimeUniverse solo = new PointInTimeUniverse().addMembership("WIN", 0);
        CrossSectionalMomentum lonely = new CrossSectionalMomentum(solo,
                new CrossSectionalMomentum.Config(10, 0, 1, 0.5));
        lonely.init(market());
        assertTrue(lonely.targetWeights(15).isEmpty());
    }

    @Test
    void endToEndMomentumProfitsAndTheNaiveUniverseOverstatesIt() {
        Map<String, BarSeries> data = market();
        // Rebalance every 5 bars, zero commission for clean arithmetic.
        PortfolioBacktester.Config config = new PortfolioBacktester.Config(1_000_000, 0, 5, 252);
        CrossSectionalMomentum.Config momo = new CrossSectionalMomentum.Config(10, 2, 1, 0.5);

        // Point-in-time run: LOSE (the short) delists at t=12 at its last price
        // (delisting return 0 — an orderly exit, not a bankruptcy windfall).
        PointInTimeUniverse universe = allMembers().recordDelisting("LOSE", 12, 0.0);
        PortfolioBacktester.Result unbiased = PortfolioBacktester.run(
                new CrossSectionalMomentum(universe, momo), data, config, universe, Map.of());

        // Naive run: no universe — the engine keeps shorting the ghost of
        // LOSE all the way down.
        PortfolioBacktester.Result naive = PortfolioBacktester.run(
                new CrossSectionalMomentum(null, momo), data, config);

        // Long-winner/short-loser makes money in both runs on this tape...
        assertTrue(unbiased.equityCurve()[BARS - 1] > 1_000_000);
        assertTrue(naive.equityCurve()[BARS - 1] > 1_000_000);
        // ...but the naive run keeps the doomed short alive and books MORE
        // profit than the honest run: survivorship bias, quantified again.
        assertTrue(naive.equityCurve()[BARS - 1] > unbiased.equityCurve()[BARS - 1],
                "naive=" + naive.equityCurve()[BARS - 1]
                        + " unbiased=" + unbiased.equityCurve()[BARS - 1]);
        assertEquals(1, unbiased.lifecycleEventsApplied());
        assertTrue(new CrossSectionalMomentum(universe, momo).name().contains("XS_MOMENTUM"));
    }

    @Test
    void configValidation() {
        assertThrows(IllegalArgumentException.class,
                () -> new CrossSectionalMomentum.Config(0, 0, 1, 0.5));
        assertThrows(IllegalArgumentException.class,
                () -> new CrossSectionalMomentum.Config(10, 10, 1, 0.5)); // skip >= lookback
        assertThrows(IllegalArgumentException.class,
                () -> new CrossSectionalMomentum.Config(10, 1, 0, 0.5));
        assertThrows(IllegalArgumentException.class,
                () -> new CrossSectionalMomentum.Config(10, 1, 1, 0));
        assertEquals(252, CrossSectionalMomentum.Config.twelveMinusOne(10).lookbackBars());
    }
}
