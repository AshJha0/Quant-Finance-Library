package com.quantfinlib.screener;

import com.quantfinlib.core.BarSeries;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic sweep of every technical filter: a monotone up-trend with
 * tiny wicks (so trend indicators saturate predictably) and its mirrored
 * down-trend, plus engineered bars for the event filters (gap, spike).
 */
class TechnicalFiltersTest {

    /**
     * Monotone accelerating trend with 1bp wicks. The slight convexity keeps
     * MACD line strictly on the trend side of its signal (a perfectly linear
     * ramp makes them converge to exact equality).
     */
    private static StockSnapshot ramp(String symbol, double step, double lastVolume) {
        BarSeries.Builder b = BarSeries.builder(symbol);
        double close = 100;
        for (int i = 0; i < 400; i++) {
            double open = close;
            double t = i + 1;
            close = 100 + step * t + Math.signum(step) * 0.0002 * t * t;
            double high = Math.max(open, close) * 1.0001;
            double low = Math.min(open, close) * 0.9999;
            double volume = i == 399 ? lastVolume : 1_000;
            b.add(i, open, high, low, close, volume);
        }
        return new StockSnapshot(symbol, b.build(), Fundamentals.unknown());
    }

    private final StockSnapshot riser = ramp("UP", 0.25, 10_000);
    private final StockSnapshot faller = ramp("DOWN", -0.15, 1_000);

    @Test
    void trendFiltersAgreeOnAPureUptrend() {
        assertTrue(TechnicalFilters.rsiAbove(14, 60).matches(riser));
        assertFalse(TechnicalFilters.rsiBelow(14, 60).matches(riser));
        assertTrue(TechnicalFilters.priceAboveSma(50).matches(riser));
        assertFalse(TechnicalFilters.priceBelowSma(50).matches(riser));
        assertTrue(TechnicalFilters.priceAboveEma(20).matches(riser));
        assertTrue(TechnicalFilters.macdBullish().matches(riser));
        assertTrue(TechnicalFilters.priceAboveVwap().matches(riser));
        assertTrue(TechnicalFilters.adxAbove(14, 20).matches(riser));
        assertTrue(TechnicalFilters.superTrendBullish(10, 3).matches(riser));
        assertTrue(TechnicalFilters.aboveIchimokuCloud().matches(riser));
        // Tiny wicks: ATR is a small fraction of price.
        assertTrue(TechnicalFilters.atrPercentBelow(14, 0.05).matches(riser));
        // A steady ramp stays inside 2 sigma: no Bollinger breakout.
        assertFalse(TechnicalFilters.bollingerBreakout(20, 2).matches(riser));
    }

    @Test
    void trendFiltersInvertOnAPureDowntrend() {
        assertTrue(TechnicalFilters.rsiBelow(14, 40).matches(faller));
        assertTrue(TechnicalFilters.priceBelowSma(50).matches(faller));
        assertFalse(TechnicalFilters.priceAboveEma(20).matches(faller));
        assertFalse(TechnicalFilters.macdBullish().matches(faller));
        assertFalse(TechnicalFilters.superTrendBullish(10, 3).matches(faller));
        assertFalse(TechnicalFilters.aboveIchimokuCloud().matches(faller));
    }

    @Test
    void rangeFiltersTrackFiftyTwoWeekLevels() {
        // The riser closes at its all-time high; the faller at its low.
        assertTrue(TechnicalFilters.near52WeekHigh(0.01).matches(riser));
        assertFalse(TechnicalFilters.near52WeekLow(0.01).matches(riser));
        assertTrue(TechnicalFilters.near52WeekLow(0.01).matches(faller));
        assertFalse(TechnicalFilters.near52WeekHigh(0.01).matches(faller));
        // Fresh highs every bar with sub-bp wicks: the last close clears
        // the previous window's highs.
        assertTrue(TechnicalFilters.breakout(20).matches(riser));
        assertFalse(TechnicalFilters.breakout(20).matches(faller));
    }

    @Test
    void eventFiltersDetectSpikesAndGaps() {
        // Riser's last bar trades 10x its average volume.
        assertTrue(TechnicalFilters.volumeSpike(20, 3).matches(riser));
        assertFalse(TechnicalFilters.volumeSpike(20, 3).matches(faller));

        // Ramp bars open at the prior close: no gap.
        assertFalse(TechnicalFilters.gapUp(0.01).matches(riser));

        // Engineered 3% gap on the final bar.
        BarSeries.Builder b = BarSeries.builder("GAP");
        for (int i = 0; i < 10; i++) {
            b.add(i, 100, 100.5, 99.5, 100, 1_000);
        }
        b.add(10, 103, 104, 102.5, 103.5, 1_000);
        StockSnapshot gapper = new StockSnapshot("GAP", b.build(), Fundamentals.unknown());
        assertTrue(TechnicalFilters.gapUp(0.02).matches(gapper));
        assertFalse(TechnicalFilters.gapUp(0.05).matches(gapper));
    }

    @Test
    void shortSeriesNeverMatchInsteadOfThrowing() {
        BarSeries.Builder b = BarSeries.builder("TINY");
        for (int i = 0; i < 5; i++) {
            b.add(i, 100, 101, 99, 100, 1_000);
        }
        StockSnapshot tiny = new StockSnapshot("TINY", b.build(), Fundamentals.unknown());
        assertFalse(TechnicalFilters.breakout(20).matches(tiny));
        assertFalse(TechnicalFilters.volumeSpike(20, 2).matches(tiny));
        assertFalse(TechnicalFilters.aboveIchimokuCloud().matches(tiny));   // NaN cloud
    }
}
