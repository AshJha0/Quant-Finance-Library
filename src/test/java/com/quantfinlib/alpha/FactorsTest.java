package com.quantfinlib.alpha;

import com.quantfinlib.core.BarSeries;
import com.quantfinlib.screener.Fundamentals;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each factor's sign convention on engineered series: trend factors score
 * risers over fallers, mean-reversion factors score the depressed name,
 * fundamental factors score cheap/profitable/calm, and the NaN warm-up and
 * no-fundamentals contracts hold.
 */
class FactorsTest {

    private static final int BARS = 120;

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

    /** Convex (sign=+1) or concave (−1) price path: momentum accelerating/decelerating. */
    private static BarSeries curved(String symbol, int sign) {
        BarSeries.Builder b = BarSeries.builder(symbol);
        for (int i = 0; i < BARS; i++) {
            // Curvature small enough to keep prices positive over the window.
            double close = 100 + 0.1 * i + sign * 0.002 * i * i;
            b.add(i, close, close * 1.0001, close * 0.9999, close, 1_000);
        }
        return b.build();
    }

    /** UP trends up, DOWN trends down, FLAT does nothing — the reference panel. */
    private static AlphaContext panel() {
        Map<String, BarSeries> data = new HashMap<>();
        data.put("UP", drift("UP", 0.01));
        data.put("DOWN", drift("DOWN", -0.01));
        data.put("FLAT", drift("FLAT", 0));
        return AlphaContext.of(data);
    }

    private static int idx(AlphaContext ctx, String symbol) {
        return ctx.symbols().indexOf(symbol);
    }

    @Test
    void contextFreezesADeterministicPanel() {
        AlphaContext ctx = panel();
        // Sorted symbol order, independent of the caller's map.
        assertEquals(java.util.List.of("DOWN", "FLAT", "UP"), ctx.symbols());
        assertEquals(BARS, ctx.bars());
        assertEquals(0.01, ctx.returnOver(idx(ctx, "UP"), 50, 51), 1e-9);
        // Misaligned series are rejected at construction.
        Map<String, BarSeries> bad = new HashMap<>();
        bad.put("A", drift("A", 0));
        BarSeries.Builder shortSeries = BarSeries.builder("B");
        shortSeries.add(0, 1, 1, 1, 1, 1);
        bad.put("B", shortSeries.build());
        assertThrows(IllegalArgumentException.class, () -> AlphaContext.of(bad));
    }

    @Test
    void trendFactorsScoreRisersAboveFallers() {
        AlphaContext ctx = panel();
        int t = BARS - 1;
        for (AlphaFactor f : new AlphaFactor[]{
                Factors.movingAverageCrossover(10, 30),
                Factors.momentum(60, 5)}) {
            double[] s = f.scores(ctx, t);
            assertTrue(s[idx(ctx, "UP")] > s[idx(ctx, "FLAT")],
                    f.name() + " must rank the riser above flat");
            assertTrue(s[idx(ctx, "FLAT")] > s[idx(ctx, "DOWN")],
                    f.name() + " must rank flat above the faller");
        }
        // MACD is a momentum-CHANGE signal: on steady exponential growth the
        // histogram converges to zero, so it needs ACCELERATING series.
        Map<String, BarSeries> accel = new HashMap<>();
        accel.put("ACCEL", curved("ACCEL", +1));
        accel.put("DECEL", curved("DECEL", -1));
        accel.put("LINEAR", drift("LINEAR", 0));
        AlphaContext ctx2 = AlphaContext.of(accel);
        double[] macd = Factors.macd(12, 26, 9).scores(ctx2, BARS - 1);
        assertTrue(macd[ctx2.symbols().indexOf("ACCEL")] > macd[ctx2.symbols().indexOf("DECEL")],
                "MACD must rank accelerating momentum above decelerating");
        // Warm-up contract: NaN before enough history.
        assertTrue(Double.isNaN(Factors.momentum(60, 5).scores(ctx, 10)[0]));
        assertTrue(Double.isNaN(Factors.movingAverageCrossover(10, 30).scores(ctx, 5)[0]));
    }

    @Test
    void meanReversionFactorsScoreTheDepressedName() {
        AlphaContext ctx = panel();
        int t = BARS - 1;
        // Contrarian orientation: the faller (depressed vs its own average,
        // oversold RSI, below its band) scores HIGHEST.
        for (AlphaFactor f : new AlphaFactor[]{
                Factors.rsi(14),
                Factors.bollinger(20, 2),
                Factors.meanReversion(30)}) {
            double[] s = f.scores(ctx, t);
            assertTrue(s[idx(ctx, "DOWN")] > s[idx(ctx, "UP")],
                    f.name() + " must score the faller above the riser (contrarian)");
        }
        // Flat series: neutral score, not NaN and not a phantom signal.
        assertEquals(0, Factors.rsi(14).scores(ctx, t)[idx(ctx, "FLAT")], 1e-12);
        assertEquals(0, Factors.bollinger(20, 2).scores(ctx, t)[idx(ctx, "FLAT")], 1e-12);
    }

    @Test
    void fundamentalFactorsScoreCheapProfitableNames() {
        Map<String, BarSeries> data = new HashMap<>();
        data.put("CHEAP", drift("CHEAP", 0));
        data.put("DEAR", drift("DEAR", 0));
        data.put("NODATA", drift("NODATA", 0));
        Map<String, Fundamentals> funda = new HashMap<>();
        // Fundamentals(marketCap, pe, pb, eps, roe, divYield, debtToEquity)
        funda.put("CHEAP", new Fundamentals(1e9, 8, 0.9, 5, 0.22, 0.03, 0.3));
        funda.put("DEAR", new Fundamentals(1e9, 40, 6.0, 5, 0.08, 0.00, 2.5));
        AlphaContext ctx = AlphaContext.of(data, funda);

        double[] value = Factors.value().scores(ctx, 50);
        double[] quality = Factors.quality().scores(ctx, 50);
        int cheap = ctx.symbols().indexOf("CHEAP");
        int dear = ctx.symbols().indexOf("DEAR");
        int nodata = ctx.symbols().indexOf("NODATA");
        // Value: 1/8 + 1/0.9 vs 1/40 + 1/6 — cheap wins by an order of magnitude.
        assertTrue(value[cheap] > value[dear]);
        // Quality: high ROE, low leverage vs low ROE, levered.
        assertTrue(quality[cheap] > quality[dear]);
        // No fundamentals → NaN, never a fabricated zero.
        assertTrue(Double.isNaN(value[nodata]));
        assertTrue(Double.isNaN(quality[nodata]));
    }

    @Test
    void lowVolatilityScoresTheCalmName() {
        Map<String, BarSeries> data = new HashMap<>();
        data.put("CALM", drift("CALM", 0.001));
        // Wild: alternating ±3% bars, ~flat drift.
        BarSeries.Builder wild = BarSeries.builder("WILD");
        double close = 100;
        for (int i = 0; i < BARS; i++) {
            double open = close;
            close = open * (i % 2 == 0 ? 1.03 : 0.97);
            wild.add(i, open, Math.max(open, close), Math.min(open, close), close, 1_000);
        }
        data.put("WILD", wild.build());
        AlphaContext ctx = AlphaContext.of(data);
        double[] s = Factors.lowVolatility(30).scores(ctx, BARS - 1);
        assertTrue(s[ctx.symbols().indexOf("CALM")] > s[ctx.symbols().indexOf("WILD")]);
    }

    @Test
    void universeGateExcludesDeadNamesFromTheCrossSection() {
        // Attach a point-in-time universe: after DOWN's delisting bar every
        // built-in factor must score it NaN — dead names never enter ICs,
        // validation, or constructed weights.
        com.quantfinlib.data.PointInTimeUniverse universe =
                new com.quantfinlib.data.PointInTimeUniverse()
                        .addMembership("UP", 0)
                        .addMembership("FLAT", 0)
                        .addMembership("DOWN", 0)
                        .recordDelisting("DOWN", 80, -1.0);
        AlphaContext ctx = panel().withUniverse(universe);
        int down = idx(ctx, "DOWN");
        // Alive before the event...
        assertTrue(!Double.isNaN(Factors.momentum(20, 0).scores(ctx, 60)[down]));
        assertTrue(ctx.isActive(down, 60));
        // ...NaN at and after it, across factor families.
        assertTrue(Double.isNaN(Factors.momentum(20, 0).scores(ctx, 90)[down]));
        assertTrue(Double.isNaN(Factors.rsi(14).scores(ctx, 90)[down]));
        assertTrue(!ctx.isActive(down, 90));
        // Survivors keep scoring.
        assertTrue(!Double.isNaN(Factors.momentum(20, 0).scores(ctx, 90)[idx(ctx, "UP")]));
    }

    @Test
    void parameterValidation() {
        assertThrows(IllegalArgumentException.class, () -> Factors.movingAverageCrossover(30, 10));
        assertThrows(IllegalArgumentException.class, () -> Factors.momentum(10, 10));
        assertThrows(IllegalArgumentException.class, () -> Factors.rsi(0));
        assertThrows(IllegalArgumentException.class, () -> Factors.bollinger(1, 2));
        assertThrows(IllegalArgumentException.class, () -> Factors.macd(26, 12, 9));
        assertThrows(IllegalArgumentException.class, () -> Factors.lowVolatility(1));
        assertTrue(Factors.value().name().equals("VALUE"));
    }
}
