package com.quantfinlib.indicators;

import com.quantfinlib.TestData;
import com.quantfinlib.core.BarSeries;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndicatorsTest {

    @Test
    void smaKnownValues() {
        double[] out = Indicators.sma(new double[]{1, 2, 3, 4, 5}, 3);
        assertTrue(Double.isNaN(out[0]));
        assertTrue(Double.isNaN(out[1]));
        assertEquals(2.0, out[2], 1e-12);
        assertEquals(3.0, out[3], 1e-12);
        assertEquals(4.0, out[4], 1e-12);
    }

    @Test
    void emaSeedsWithSmaAndConverges() {
        double[] flat = new double[50];
        java.util.Arrays.fill(flat, 10.0);
        double[] out = Indicators.ema(flat, 10);
        assertEquals(10.0, out[9], 1e-12);
        assertEquals(10.0, out[49], 1e-12);
    }

    @Test
    void wmaWeightsRecentMore() {
        double[] out = Indicators.wma(new double[]{1, 2, 3}, 3);
        // (1*1 + 2*2 + 3*3) / 6 = 14/6
        assertEquals(14.0 / 6, out[2], 1e-12);
    }

    @Test
    void rsiExtremesAndRange() {
        double[] up = new double[30];
        for (int i = 0; i < 30; i++) {
            up[i] = 100 + i;
        }
        double[] rsi = Indicators.rsi(up, 14);
        assertEquals(100.0, rsi[29], 1e-9);

        BarSeries s = TestData.gbmSeries("X", 300, 100, 0.05, 0.2, 1);
        double[] r = Indicators.rsi(s.closes(), 14);
        for (int i = 14; i < r.length; i++) {
            assertTrue(r[i] >= 0 && r[i] <= 100, "rsi out of range at " + i + ": " + r[i]);
        }
    }

    @Test
    void macdHistogramIsLineMinusSignal() {
        double[] close = TestData.gbmSeries("X", 200, 100, 0.1, 0.2, 2).closes();
        Indicators.Macd m = Indicators.macd(close, 12, 26, 9);
        int last = close.length - 1;
        assertFalse(Double.isNaN(m.signal()[last]));
        assertEquals(m.line()[last] - m.signal()[last], m.histogram()[last], 1e-12);
    }

    @Test
    void bollingerBandsBracketMiddle() {
        double[] close = TestData.gbmSeries("X", 100, 100, 0, 0.3, 3).closes();
        Indicators.Bollinger b = Indicators.bollinger(close, 20, 2);
        for (int i = 19; i < close.length; i++) {
            assertTrue(b.upper()[i] >= b.middle()[i]);
            assertTrue(b.lower()[i] <= b.middle()[i]);
        }
    }

    @Test
    void atrPositiveAfterWarmup() {
        BarSeries s = TestData.gbmSeries("X", 100, 100, 0.05, 0.25, 4);
        double[] atr = Indicators.atr(s, 14);
        assertTrue(Double.isNaN(atr[12]));
        for (int i = 13; i < s.size(); i++) {
            assertTrue(atr[i] > 0);
        }
    }

    @Test
    void adxWithinRange() {
        BarSeries s = TestData.gbmSeries("X", 300, 100, 0.1, 0.25, 5);
        Indicators.Adx adx = Indicators.adx(s, 14);
        int last = s.size() - 1;
        assertFalse(Double.isNaN(adx.adx()[last]));
        assertTrue(adx.adx()[last] >= 0 && adx.adx()[last] <= 100);
        assertTrue(adx.plusDi()[last] >= 0 && adx.minusDi()[last] >= 0);
    }

    @Test
    void vwapObvCmfCciWilliamsRProduceValues() {
        BarSeries s = TestData.gbmSeries("X", 150, 100, 0.08, 0.2, 6);
        int last = s.size() - 1;
        assertTrue(Indicators.vwap(s)[last] > 0);
        assertFalse(Double.isNaN(Indicators.obv(s)[last]));
        double cmf = Indicators.cmf(s, 20)[last];
        assertTrue(cmf >= -1 && cmf <= 1);
        assertFalse(Double.isNaN(Indicators.cci(s, 20)[last]));
        double wr = Indicators.williamsR(s, 14)[last];
        assertTrue(wr <= 0 && wr >= -100);
    }

    @Test
    void superTrendDirectionFollowsStrongTrend() {
        // Strongly rising market: direction should end bullish.
        BarSeries s = TestData.gbmSeries("X", 400, 100, 0.5, 0.08, 7);
        Indicators.SuperTrend st = Indicators.superTrend(s, 10, 3);
        assertEquals(1, st.direction()[s.size() - 1]);
        assertFalse(Double.isNaN(st.value()[s.size() - 1]));
    }

    @Test
    void ichimokuCloudSpansPresent() {
        BarSeries s = TestData.gbmSeries("X", 300, 100, 0.1, 0.2, 8);
        Indicators.Ichimoku ich = Indicators.ichimoku(s, 9, 26, 52);
        int last = s.size() - 1;
        assertFalse(Double.isNaN(ich.tenkan()[last]));
        assertFalse(Double.isNaN(ich.kijun()[last]));
        assertFalse(Double.isNaN(ich.senkouA()[last]));
        assertFalse(Double.isNaN(ich.senkouB()[last]));
        // chikou is close displaced backwards
        assertEquals(s.close(last), ich.chikou()[last - 26], 1e-12);
    }

    @Test
    void stochasticRsiWithinRange() {
        double[] close = TestData.gbmSeries("X", 300, 100, 0.05, 0.25, 9).closes();
        Indicators.StochRsi sr = Indicators.stochasticRsi(close, 14, 14, 3, 3);
        int last = close.length - 1;
        assertFalse(Double.isNaN(sr.k()[last]));
        assertTrue(sr.k()[last] >= 0 && sr.k()[last] <= 100);
        assertTrue(sr.d()[last] >= 0 && sr.d()[last] <= 100);
    }

    @Test
    void parabolicSarStaysOnCorrectSideInTrend() {
        BarSeries s = TestData.gbmSeries("X", 300, 100, 0.6, 0.06, 10);
        double[] sar = Indicators.parabolicSar(s, 0.02, 0.02, 0.2);
        assertFalse(Double.isNaN(sar[s.size() - 1]));
        // In a strong uptrend the last SAR should sit below the close.
        assertTrue(sar[s.size() - 1] < s.close(s.size() - 1));
    }

    @Test
    void keltnerAndDonchianChannelsOrdered() {
        BarSeries s = TestData.gbmSeries("X", 120, 100, 0.05, 0.2, 11);
        int last = s.size() - 1;
        Indicators.Keltner k = Indicators.keltner(s, 20, 10, 2);
        assertTrue(k.upper()[last] > k.middle()[last] && k.middle()[last] > k.lower()[last]);
        Indicators.Donchian d = Indicators.donchian(s, 20);
        assertTrue(d.upper()[last] >= d.middle()[last] && d.middle()[last] >= d.lower()[last]);
    }

    @Test
    void rocAndMomentum() {
        double[] v = {100, 110, 121};
        assertEquals(21.0, Indicators.roc(v, 2)[2], 1e-9);
        assertEquals(21.0, Indicators.momentum(v, 2)[2], 1e-9);
    }
}
