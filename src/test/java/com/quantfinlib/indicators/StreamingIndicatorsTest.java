package com.quantfinlib.indicators;

import com.quantfinlib.TestData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The streaming indicators must match the batch engine value-for-value
 * (including NaN warm-up positions) so live behavior equals backtested behavior.
 */
class StreamingIndicatorsTest {

    private static final double[] CLOSES =
            TestData.gbmSeries("X", 500, 100, 0.08, 0.25, 99).closes();

    private static void assertSame(double expected, double actual, int index, String what) {
        if (Double.isNaN(expected)) {
            assertTrue(Double.isNaN(actual), what + " expected NaN at " + index + " but was " + actual);
        } else {
            assertEquals(expected, actual, 1e-9, what + " mismatch at " + index);
        }
    }

    @Test
    void smaMatchesBatch() {
        double[] batch = Indicators.sma(CLOSES, 20);
        StreamingIndicators.Sma s = new StreamingIndicators.Sma(20);
        for (int i = 0; i < CLOSES.length; i++) {
            assertSame(batch[i], s.update(CLOSES[i]), i, "SMA");
        }
    }

    @Test
    void emaMatchesBatch() {
        double[] batch = Indicators.ema(CLOSES, 20);
        StreamingIndicators.Ema e = new StreamingIndicators.Ema(20);
        for (int i = 0; i < CLOSES.length; i++) {
            assertSame(batch[i], e.update(CLOSES[i]), i, "EMA");
        }
    }

    @Test
    void rsiMatchesBatch() {
        double[] batch = Indicators.rsi(CLOSES, 14);
        StreamingIndicators.Rsi r = new StreamingIndicators.Rsi(14);
        for (int i = 0; i < CLOSES.length; i++) {
            assertSame(batch[i], r.update(CLOSES[i]), i, "RSI");
        }
    }

    @Test
    void macdMatchesBatchIncludingSignalAndHistogram() {
        Indicators.Macd batch = Indicators.macd(CLOSES, 12, 26, 9);
        StreamingIndicators.Macd m = new StreamingIndicators.Macd(12, 26, 9);
        for (int i = 0; i < CLOSES.length; i++) {
            double line = m.update(CLOSES[i]);
            assertSame(batch.line()[i], line, i, "MACD line");
            assertSame(batch.signal()[i], m.signal(), i, "MACD signal");
            assertSame(batch.histogram()[i], m.histogram(), i, "MACD histogram");
        }
    }

    @Test
    void vwapAccumulates() {
        StreamingIndicators.Vwap v = new StreamingIndicators.Vwap();
        v.update(10, 100);
        double res = v.update(20, 100);
        assertEquals(15.0, res, 1e-12);
        assertEquals(15.0, v.value(), 1e-12);
    }
}
