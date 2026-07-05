package com.quantfinlib.data;

import com.quantfinlib.backtest.portfolio.PortfolioBacktester;
import com.quantfinlib.backtest.portfolio.PortfolioStrategy;
import com.quantfinlib.core.BarSeries;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeriesAlignerTest {

    /** Series with the given timestamps; close = 100 + ts. */
    private static BarSeries series(String symbol, long... timestamps) {
        BarSeries.Builder b = BarSeries.builder(symbol);
        for (long ts : timestamps) {
            double px = 100 + ts;
            b.add(ts, px, px, px, px, 1_000);
        }
        return b.build();
    }

    @Test
    void intersectKeepsOnlyCommonTimestamps() {
        Map<String, BarSeries> input = new LinkedHashMap<>();
        input.put("A", series("A", 1, 2, 3, 4, 5));
        input.put("B", series("B", 2, 3, 5, 6));        // missing 1, 4
        input.put("C", series("C", 1, 2, 3, 5, 7));     // missing 4

        Map<String, BarSeries> aligned = SeriesAligner.intersect(input);
        for (BarSeries s : aligned.values()) {
            assertEquals(3, s.size());                   // ts 2, 3, 5
            assertEquals(2, s.timestamp(0));
            assertEquals(5, s.timestamp(2));
        }
        // Values preserved, not resampled.
        assertEquals(103, aligned.get("B").close(1), 1e-12);
        // Aligned output runs straight through the portfolio backtester.
        PortfolioBacktester.Result r = PortfolioBacktester.run(new PortfolioStrategy() {
            @Override
            public String name() {
                return "EW";
            }

            @Override
            public void init(Map<String, BarSeries> data) {
            }

            @Override
            public Map<String, Double> targetWeights(int index) {
                return Map.of("A", 0.5, "B", 0.5);
            }
        }, aligned, PortfolioBacktester.Config.defaults());
        assertEquals(3, r.equityCurve().length);
    }

    @Test
    void unionForwardFillCarriesLastClose() {
        Map<String, BarSeries> input = new LinkedHashMap<>();
        input.put("A", series("A", 1, 2, 4));           // gap at 3
        input.put("B", series("B", 2, 3, 4));           // starts later

        Map<String, BarSeries> aligned = SeriesAligner.unionForwardFill(input);
        // Timeline starts at max first-ts (2) and covers 2, 3, 4.
        for (BarSeries s : aligned.values()) {
            assertEquals(3, s.size());
            assertEquals(2, s.timestamp(0));
        }
        BarSeries a = aligned.get("A");
        // A had no bar at ts=3: forward-filled flat at the prior close, volume 0.
        assertEquals(102, a.close(1), 1e-12);
        assertEquals(102, a.high(1), 1e-12);
        assertEquals(0, a.volume(1), 1e-12);
        assertEquals(104, a.close(2), 1e-12);            // real bar resumes
        assertEquals(1_000, a.volume(2), 1e-12);
    }

    @Test
    void degenerateInputsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> SeriesAligner.intersect(Map.of()));
        Map<String, BarSeries> disjoint = new LinkedHashMap<>();
        disjoint.put("A", series("A", 1, 2));
        disjoint.put("B", series("B", 10, 11));
        assertThrows(IllegalArgumentException.class, () -> SeriesAligner.intersect(disjoint));
        // Union still works for disjoint (B's start trims the timeline).
        Map<String, BarSeries> union = SeriesAligner.unionForwardFill(disjoint);
        assertEquals(2, union.get("A").size());
        assertTrue(union.get("A").volume(0) == 0);       // A forward-filled throughout
    }
}
