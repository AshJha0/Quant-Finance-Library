package com.quantfinlib.backtest.portfolio;

import com.quantfinlib.backtest.FillPolicy;
import com.quantfinlib.core.BarSeries;
import com.quantfinlib.data.PointInTimeUniverse;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fill timing, financing and adjusted/unadjusted price pairs on moving prices. */
class PortfolioTimingTest {

    private static final long DAY = 86_400_000L;

    private static BarSeries oc(String symbol, double[][] openClose) {
        BarSeries.Builder b = BarSeries.builder(symbol);
        for (int i = 0; i < openClose.length; i++) {
            double o = openClose[i][0];
            double c = openClose[i][1];
            b.add(i * DAY, o, Math.max(o, c), Math.min(o, c), c, 1_000);
        }
        return b.build();
    }

    /** 100% in "A" decided at bar 0, nothing afterwards. */
    private static PortfolioStrategy allIn(String symbol) {
        return new PortfolioStrategy() {
            @Override
            public String name() {
                return "all-in";
            }

            @Override
            public void init(Map<String, BarSeries> data) {
            }

            @Override
            public Map<String, Double> targetWeights(int index) {
                return index == 0 ? Map.of(symbol, 1.0) : Map.of(symbol, 1.0);
            }
        };
    }

    private static final double[][] A = {{100, 100}, {104, 110}, {111, 121}, {120, 120}};

    @Test
    void nextOpenFillsAtTheNextOpenOnMovingPrices() {
        Map<String, BarSeries> data = Map.of("A", oc("A", A));
        PortfolioBacktester.Config base = new PortfolioBacktester.Config(1_000, 0, 1000, 252, null);
        PortfolioBacktester.Result close = PortfolioBacktester.run(allIn("A"), data,
                base.withFillPolicy(FillPolicy.CLOSE));
        PortfolioBacktester.Result open = PortfolioBacktester.run(allIn("A"), data, base);
        PortfolioBacktester.Result nextClose = PortfolioBacktester.run(allIn("A"), data,
                base.withFillPolicy(FillPolicy.NEXT_CLOSE));
        // CLOSE: 10 shares at 100 -> 1200 at the end.
        assertEquals(1_200, close.equityCurve()[3], 1e-9);
        // NEXT_OPEN: 1000/104 shares bought at the open of bar 1.
        assertEquals(1_000.0 / 104 * 120, open.equityCurve()[3], 1e-9);
        assertEquals(1_000.0, open.equityCurve()[0], 1e-12);
        // NEXT_CLOSE: bought at close of bar 1 (110).
        assertEquals(1_000.0 / 110 * 120, nextClose.equityCurve()[3], 1e-9);
    }

    @Test
    void delistingOnAMovingPriceUsesTheLastCloseBeforeTheEvent() {
        // B rises then delists at bar 3 with -50%: proceeds = close(2) * 0.5.
        Map<String, BarSeries> data = new HashMap<>();
        data.put("B", oc("B", new double[][]{{100, 100}, {100, 110}, {112, 120}, {120, 120}, {120, 120}}));
        PointInTimeUniverse u = new PointInTimeUniverse().addMembership("B", 0)
                .recordDelisting("B", 3 * DAY, -0.5);
        PortfolioBacktester.Result r = PortfolioBacktester.run(allIn("B"), data,
                new PortfolioBacktester.Config(1_000, 0, 1000, 252, null), u, Map.of());
        double shares = 1_000.0 / 100; // bought at the open of bar 1
        assertEquals(shares * 120 * 0.5, r.equityCurve()[3], 1e-9);
        assertEquals(shares * 120 * 0.5, r.equityCurve()[4], 1e-9);
        assertEquals(1, r.lifecycleEventsApplied());
    }

    @Test
    void financingChargesBorrowAndMarginAndCreditsCash() {
        Map<String, BarSeries> data = new HashMap<>();
        double[][] flat = new double[11][];
        for (int i = 0; i < 11; i++) {
            flat[i] = new double[]{100, 100};
        }
        data.put("L", oc("L", flat));
        data.put("S", oc("S", flat));
        PortfolioStrategy ls = new PortfolioStrategy() {
            @Override
            public String name() {
                return "ls";
            }

            @Override
            public void init(Map<String, BarSeries> d) {
            }

            @Override
            public Map<String, Double> targetWeights(int index) {
                return Map.of("L", 1.5, "S", -0.5);
            }
        };
        PortfolioBacktester.Financing terms = new PortfolioBacktester.Financing(
                0.0252, 0.0504, 252, Map.of("S", 504.0));
        PortfolioBacktester.Result r = PortfolioBacktester.run(ls, data,
                new PortfolioBacktester.Config(10_000, 0, 1000, 252, null)
                        .withFillPolicy(FillPolicy.CLOSE).withFinancing(terms), null, Map.of());
        // Book: long 15,000, short 5,000, cash 10,000 - 15,000 + 5,000 = 0.
        // Borrow on S at 504 bp/yr: 5,000 * 0.0504/252 = 1 per bar, paid from
        // cash, which then goes negative and pays the 5.04% margin rate.
        double cash = 0;
        for (int bar = 1; bar <= 10; bar++) {
            double charge = 1 + (cash < 0 ? -cash * 0.0504 / 252 : -cash * 0.0252 / 252);
            cash -= charge;
        }
        assertEquals(10_000 + cash, r.equityCurve()[10], 1e-9);
        assertEquals(-cash, r.financingCost(), 1e-9);
        assertTrue(-cash > 10, "margin interest on the borrowed fee");
    }

    @Test
    void pricePairOverloadSignalsOnAdjustedAndTradesUnadjusted() {
        // A 2:1 split at bar 3: unadjusted price halves, adjusted is smooth.
        double[][] raw = {{100, 100}, {101, 101}, {102, 102}, {51.5, 51.5}, {52, 52}, {52.5, 52.5}};
        double[][] adj = {{50, 50}, {50.5, 50.5}, {51, 51}, {51.5, 51.5}, {52, 52}, {52.5, 52.5}};
        Map<String, BarSeries> unadjusted = Map.of("A", oc("A", raw));
        Map<String, BarSeries> adjusted = Map.of("A", oc("A", adj));
        double[] seen = new double[1];
        PortfolioStrategy s = new PortfolioStrategy() {
            Map<String, BarSeries> signal;

            @Override
            public String name() {
                return "pair";
            }

            @Override
            public void init(Map<String, BarSeries> data) {
                signal = data;
            }

            @Override
            public Map<String, Double> targetWeights(int index) {
                if (index == 4) {
                    seen[0] = signal.get("A").close(4) / signal.get("A").close(1) - 1;
                }
                return Map.of();
            }
        };
        PortfolioBacktester.run(s, unadjusted, adjusted,
                PortfolioBacktester.Config.defaults(), null, Map.of());
        assertEquals(52.0 / 50.5 - 1, seen[0], 1e-12);
        assertTrue(seen[0] > 0, "the split must not read as a crash");
    }
}
