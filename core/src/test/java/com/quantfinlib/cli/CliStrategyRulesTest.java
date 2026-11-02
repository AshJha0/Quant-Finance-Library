package com.quantfinlib.cli;

import com.quantfinlib.TestData;
import com.quantfinlib.backtest.Signal;
import com.quantfinlib.backtest.TradingStrategy;
import com.quantfinlib.core.BarSeries;
import com.quantfinlib.indicators.Indicators;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bar-by-bar rules of the CLI's private sample strategies (the 2.x
 * {@code backtest.strategies} types, decision D3). Each signal sequence is
 * compared with the rule written out here from the indicator arrays, on
 * several seeded paths, so a changed comparison or a dropped NaN guard shows up
 * as a different signal on some bar. Own test; the rules are the ones in each
 * class's javadoc.
 */
class CliStrategyRulesTest {

    private static final long[] SEEDS = {3, 11, 29, 47, 71};

    private static BarSeries path(long seed) {
        return TestData.gbmSeries("P" + seed, 400, 100, 0.0, 0.35, seed);
    }

    private interface Rule {
        Signal at(int i);
    }

    private static int compare(TradingStrategy s, BarSeries series, Rule rule) {
        s.init(series);
        int trades = 0;
        for (int i = 0; i < series.size(); i++) {
            Signal expected = rule.at(i);
            assertEquals(expected, s.onBar(i), s.name() + " bar " + i);
            if (expected != Signal.HOLD) {
                trades++;
            }
        }
        return trades;
    }

    private static boolean crossUp(double[] a, double[] b, int i) {
        return a[i - 1] <= b[i - 1] && a[i] > b[i];
    }

    private static boolean crossDown(double[] a, double[] b, int i) {
        return a[i - 1] >= b[i - 1] && a[i] < b[i];
    }

    @Test
    void emaCrossSignalsOnTheBarTheFastEmaCrosses() {
        int total = 0;
        for (long seed : SEEDS) {
            BarSeries s = path(seed);
            double[] f = Indicators.ema(s.closesCopy(), 5);
            double[] w = Indicators.ema(s.closesCopy(), 20);
            total += compare(new EmaCrossStrategy(5, 20), s, i -> {
                if (i < 1 || Double.isNaN(w[i]) || Double.isNaN(w[i - 1])) {
                    return Signal.HOLD;
                }
                return crossUp(f, w, i) ? Signal.BUY : crossDown(f, w, i) ? Signal.SELL : Signal.HOLD;
            });
        }
        assertTrue(total > 10, "paths must exercise both crossings, got " + total);
        assertEquals("EMA_CROSS(5,20)", new EmaCrossStrategy(5, 20).name());
        assertThrows(IllegalArgumentException.class, () -> new EmaCrossStrategy(20, 20));
        assertThrows(IllegalArgumentException.class, () -> new EmaCrossStrategy(21, 20));
    }

    @Test
    void rsiSignalsWhenItLeavesTheOversoldOrOverboughtZone() {
        int total = 0;
        for (long seed : SEEDS) {
            BarSeries s = path(seed);
            double[] r = Indicators.rsi(s.closesCopy(), 7);
            total += compare(new RsiStrategy(7, 30, 70), s, i -> {
                if (i < 1 || Double.isNaN(r[i]) || Double.isNaN(r[i - 1])) {
                    return Signal.HOLD;
                }
                if (r[i - 1] <= 30 && r[i] > 30) {
                    return Signal.BUY;
                }
                if (r[i - 1] >= 70 && r[i] < 70) {
                    return Signal.SELL;
                }
                return Signal.HOLD;
            });
        }
        assertTrue(total > 10, "paths must exercise both zones, got " + total);
        assertEquals("RSI(14,25.0,75.0)", new RsiStrategy(14, 25, 75).name());
    }

    @Test
    void macdSignalsOnSignalLineCrossings() {
        int total = 0;
        for (long seed : SEEDS) {
            BarSeries s = path(seed);
            Indicators.Macd m = Indicators.macd(s.closesCopy(), 6, 13, 5);
            double[] line = m.lineCopy();
            double[] sig = m.signalCopy();
            total += compare(new MacdStrategy(6, 13, 5), s, i -> {
                if (i < 1 || Double.isNaN(sig[i]) || Double.isNaN(sig[i - 1])) {
                    return Signal.HOLD;
                }
                return crossUp(line, sig, i) ? Signal.BUY : crossDown(line, sig, i) ? Signal.SELL : Signal.HOLD;
            });
        }
        assertTrue(total > 10, "paths must exercise both crossings, got " + total);
        assertEquals("MACD(12,26,9)", new MacdStrategy().name());
        assertEquals("MACD(6,13,5)", new MacdStrategy(6, 13, 5).name());
    }

    @Test
    void bollingerBuysBelowTheLowerBandAndSellsOnTheWayBack() {
        int buys = 0;
        int sells = 0;
        for (long seed : SEEDS) {
            BarSeries s = path(seed);
            double[] c = s.closesCopy();
            Indicators.Bollinger b = Indicators.bollinger(c, 10, 1.5);
            double[] up = b.upperCopy();
            double[] mid = b.middleCopy();
            double[] lo = b.lowerCopy();
            TradingStrategy st = new BollingerBandsStrategy(10, 1.5);
            st.init(s);
            for (int i = 0; i < c.length; i++) {
                Signal expected;
                if (Double.isNaN(mid[i])) {
                    expected = Signal.HOLD;
                } else if (c[i] < lo[i]) {
                    expected = Signal.BUY;
                } else if ((c[i] >= mid[i] && c[i] > up[i])
                        || (c[i] >= mid[i] && i > 0 && c[i - 1] < mid[i - 1])) {
                    expected = Signal.SELL;
                } else {
                    expected = Signal.HOLD;
                }
                assertEquals(expected, st.onBar(i), "bar " + i + " seed " + seed);
                buys += expected == Signal.BUY ? 1 : 0;
                sells += expected == Signal.SELL ? 1 : 0;
            }
        }
        assertTrue(buys > 5 && sells > 5, "buys=" + buys + " sells=" + sells);
        assertEquals("BOLLINGER(20,2.0)", new BollingerBandsStrategy().name());
    }

    // ---- exact-equality bars: flat prices make the two lines equal, a step
    // ---- then crosses them; these pin <= against < and > against >=.

    private static double[] flatThenStep(int flat, double level, double step, int after) {
        double[] c = new double[flat + after];
        for (int i = 0; i < c.length; i++) {
            c[i] = i < flat ? level : level + step;
        }
        return c;
    }

    private static Signal[] run(TradingStrategy s, double[] closes) {
        BarSeries series = BarSeries.of("X", closes);
        s.init(series);
        Signal[] out = new Signal[closes.length];
        for (int i = 0; i < closes.length; i++) {
            out[i] = s.onBar(i);
        }
        return out;
    }

    private static void allHold(Signal[] sig, String what) {
        for (int i = 0; i < sig.length; i++) {
            assertEquals(Signal.HOLD, sig[i], what + " bar " + i);
        }
    }

    @Test
    void crossoversOnFlatPricesAndOnAStepFromEquality() {
        double[] flat = flatThenStep(60, 100, 0, 0);
        allHold(run(new EmaCrossStrategy(3, 8), flat), "EMA flat");
        allHold(run(new MacdStrategy(3, 8, 4), flat), "MACD flat");
        // lines equal on bar 39, the step on bar 40 lifts the fast line above
        Signal[] up = run(new EmaCrossStrategy(3, 8), flatThenStep(40, 100, 1, 10));
        assertEquals(Signal.BUY, up[40]);
        Signal[] down = run(new EmaCrossStrategy(3, 8), flatThenStep(40, 100, -1, 10));
        assertEquals(Signal.SELL, down[40]);
        Signal[] mUp = run(new MacdStrategy(3, 8, 4), flatThenStep(40, 100, 1, 10));
        assertEquals(Signal.BUY, mUp[40]);
        Signal[] mDown = run(new MacdStrategy(3, 8, 4), flatThenStep(40, 100, -1, 10));
        assertEquals(Signal.SELL, mDown[40]);
    }

    @Test
    void rsiThresholdsAreInclusiveOnTheWayInAndStrictOnTheWayOut() {
        BarSeries s = path(11);
        double[] r = Indicators.rsi(s.closesCopy(), 7);
        int k = -1;
        for (int i = 2; i < r.length; i++) {
            if (!Double.isNaN(r[i - 1]) && r[i] > r[i - 1] + 1 && r[i - 1] > 5 && r[i] < 95) {
                k = i;
                break;
            }
        }
        assertTrue(k > 0);
        // oversold exactly at r[k-1]: rsi[k-1] <= oversold < rsi[k] -> BUY at k
        assertEquals(Signal.BUY, run(new RsiStrategy(7, r[k - 1], 100), s.closesCopy())[k]);
        // oversold exactly at r[k]: rsi[k] is not > oversold -> no BUY at k
        assertEquals(Signal.HOLD, run(new RsiStrategy(7, r[k], 100), s.closesCopy())[k]);
        int j = -1;
        for (int i = 2; i < r.length; i++) {
            if (!Double.isNaN(r[i - 1]) && r[i] < r[i - 1] - 1 && r[i] > 5 && r[i - 1] < 95) {
                j = i;
                break;
            }
        }
        assertTrue(j > 0);
        // overbought exactly at r[j-1]: rsi[j-1] >= overbought > rsi[j] -> SELL at j
        assertEquals(Signal.SELL, run(new RsiStrategy(7, 0, r[j - 1]), s.closesCopy())[j]);
        // overbought exactly at r[j]: rsi[j] is not < overbought -> no SELL at j
        assertEquals(Signal.HOLD, run(new RsiStrategy(7, 0, r[j]), s.closesCopy())[j]);
    }

    @Test
    void bollingerOnFlatPricesAndAtTheBands() {
        // zero dispersion: close equals all three bands, so no signal at all
        allHold(run(new BollingerBandsStrategy(5, 2), flatThenStep(30, 100, 0, 0)), "flat");
        // period 1: bands equal the close from bar 0, so bar 0 is evaluated and
        // must not look at bar -1
        allHold(run(new BollingerBandsStrategy(1, 2), flatThenStep(5, 100, 0, 0)), "period 1");
        // a step up from a flat window: with k = 1 the close is above the upper
        // band (window 100 x4, 101: mean 100.2, one standard deviation below 0.8) -> SELL
        Signal[] up = run(new BollingerBandsStrategy(5, 1), flatThenStep(20, 100, 1, 3));
        assertEquals(Signal.SELL, up[20]);
        // the mirror step down: close below the lower band -> BUY
        Signal[] down = run(new BollingerBandsStrategy(5, 1), flatThenStep(20, 100, -1, 3));
        assertEquals(Signal.BUY, down[20]);
    }
}
