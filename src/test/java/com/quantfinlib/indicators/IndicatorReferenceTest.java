package com.quantfinlib.indicators;

import com.quantfinlib.core.BarSeries;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Indicator values pinned to published references and to independent
 * implementations written from Wilder's (1978) definitions, not to the
 * library's own batch/streaming parity.
 */
class IndicatorReferenceTest {

    /**
     * The 14-period RSI worked example published by StockCharts
     * ("Relative Strength Index (RSI)", ChartSchool), closes to four decimals
     * as tabulated there, RSI values to two decimals.
     */
    private static final double[] RSI_CLOSES = {
            44.3389, 44.0902, 44.1497, 43.6124, 44.3278, 44.8264, 45.0955, 45.4245,
            45.8433, 46.0826, 45.8931, 46.0328, 45.6140, 46.2820, 46.2820, 46.0028,
            46.0328, 46.4116, 46.2222, 45.6439, 46.2122, 46.2521, 45.7137, 46.4515,
            45.7835, 45.3548, 44.0288, 44.1783, 44.2181, 44.5672, 43.4205, 42.6628,
            43.1314};
    private static final double[] RSI_PUBLISHED = {
            70.53, 66.32, 66.55, 69.41, 66.36, 57.97, 62.93, 63.26, 56.06, 62.38,
            54.71, 50.42, 39.99, 41.46, 41.87, 45.46, 37.30, 33.08, 37.77};

    @Test
    void wilderRsiMatchesThePublishedWorkedExample() {
        double[] rsi = Indicators.rsi(RSI_CLOSES, 14);
        for (int k = 0; k < RSI_PUBLISHED.length; k++) {
            assertEquals(RSI_PUBLISHED[k], rsi[14 + k], 0.005, "RSI at bar " + (14 + k));
        }
        for (int i = 0; i < 14; i++) {
            assertTrue(Double.isNaN(rsi[i]));
        }
        StreamingIndicators.Rsi live = new StreamingIndicators.Rsi(14);
        double last = Double.NaN;
        for (double c : RSI_CLOSES) {
            last = live.update(c);
        }
        assertEquals(37.77, last, 0.005);
    }

    private static BarSeries randomOhlc(long seed, int n) {
        SplittableRandom rng = new SplittableRandom(seed);
        BarSeries.Builder b = BarSeries.builder("R");
        double c = 100;
        for (int i = 0; i < n; i++) {
            double o = c * (1 + 0.01 * (rng.nextDouble() - 0.5));
            c = o * (1 + 0.02 * (rng.nextDouble() - 0.5));
            double h = Math.max(o, c) * (1 + 0.01 * rng.nextDouble());
            double l = Math.min(o, c) * (1 - 0.01 * rng.nextDouble());
            b.add(i, o, h, l, c, 1_000);
        }
        return b.build();
    }

    /**
     * ATR from Wilder's definition: true range, first value the simple mean
     * of the first {@code p} true ranges, then {@code ATR_t = (ATR_{t-1}(p-1) + TR_t)/p}.
     * Bar 0's true range is high minus low (no previous close).
     */
    @Test
    void atrMatchesWildersDefinition() {
        BarSeries s = randomOhlc(5, 80);
        int p = 14;
        double[] tr = new double[s.size()];
        for (int i = 0; i < s.size(); i++) {
            double hl = s.high(i) - s.low(i);
            tr[i] = i == 0 ? hl : Math.max(hl, Math.max(Math.abs(s.high(i) - s.close(i - 1)),
                    Math.abs(s.low(i) - s.close(i - 1))));
        }
        double atr = 0;
        for (int i = 0; i < p; i++) {
            atr += tr[i] / p;
        }
        double[] lib = Indicators.atr(s, p);
        assertEquals(atr, lib[p - 1], 1e-12);
        for (int i = p; i < s.size(); i++) {
            atr = (atr * (p - 1) + tr[i]) / p;
            assertEquals(atr, lib[i], 1e-12);
        }
    }

    /**
     * ADX from Wilder's definition: directional movements, Wilder sums
     * {@code S_t = S_{t-1} − S_{t-1}/p + x_t} seeded with the plain sum of the
     * first {@code p} values (bars 1..p), DI = 100 S(DM)/S(TR),
     * DX = 100 |DI+ − DI−|/(DI+ + DI−), ADX seeded with the mean of the first
     * {@code p} DX values and then Wilder-averaged.
     */
    @Test
    void adxMatchesWildersDefinition() {
        BarSeries s = randomOhlc(9, 120);
        int p = 14;
        int n = s.size();
        double[] tr = new double[n];
        double[] pdm = new double[n];
        double[] mdm = new double[n];
        for (int i = 1; i < n; i++) {
            double up = s.high(i) - s.high(i - 1);
            double dn = s.low(i - 1) - s.low(i);
            pdm[i] = up > dn && up > 0 ? up : 0;
            mdm[i] = dn > up && dn > 0 ? dn : 0;
            tr[i] = Math.max(s.high(i) - s.low(i), Math.max(Math.abs(s.high(i) - s.close(i - 1)),
                    Math.abs(s.low(i) - s.close(i - 1))));
        }
        double str = 0;
        double sp = 0;
        double sm = 0;
        for (int i = 1; i <= p; i++) {
            str += tr[i];
            sp += pdm[i];
            sm += mdm[i];
        }
        double[] dx = new double[n];
        double[] pdi = new double[n];
        for (int i = p; i < n; i++) {
            if (i > p) {
                str = str - str / p + tr[i];
                sp = sp - sp / p + pdm[i];
                sm = sm - sm / p + mdm[i];
            }
            double plus = 100 * sp / str;
            double minus = 100 * sm / str;
            pdi[i] = plus;
            dx[i] = 100 * Math.abs(plus - minus) / (plus + minus);
        }
        Indicators.Adx lib = Indicators.adx(s, p);
        double adx = 0;
        for (int i = p; i < 2 * p; i++) {
            adx += dx[i] / p;
        }
        assertEquals(adx, lib.adx()[2 * p - 1], 1e-10);
        for (int i = 2 * p; i < n; i++) {
            adx = (adx * (p - 1) + dx[i]) / p;
            assertEquals(adx, lib.adx()[i], 1e-10);
            assertEquals(pdi[i], lib.plusDi()[i], 1e-10);
        }
    }

    @Test
    void streamingSmaDoesNotDriftOverManyTicks() {
        // 2e7 updates of prices near 1e4 with large swings: the resynced sum
        // matches the exact window mean to ~1 ulp of the price scale.
        int period = 50;
        StreamingIndicators.Sma sma = new StreamingIndicators.Sma(period);
        SplittableRandom rng = new SplittableRandom(3);
        double[] ring = new double[period];
        double v = 0;
        for (int i = 0; i < 20_000_000; i++) {
            double x = 10_000 + 5_000 * (rng.nextDouble() - 0.5) + (i % 7) * 1e-3;
            ring[i % period] = x;
            v = sma.update(x);
        }
        double exact = 0;
        for (double x : ring) {
            exact += x;
        }
        exact /= period;
        assertEquals(exact, v, 1e-9);
        // Batch and streaming stay bit-identical on the resync schedule.
        double[] series = new double[1_000];
        for (int i = 0; i < series.length; i++) {
            series[i] = 10_000 + 3_000 * Math.sin(i * 0.37);
        }
        double[] batch = Indicators.sma(series, 7);
        StreamingIndicators.Sma s2 = new StreamingIndicators.Sma(7);
        for (int i = 0; i < series.length; i++) {
            double live = s2.update(series[i]);
            if (i >= 6) {
                assertEquals(batch[i], live, 0.0);
            }
        }
    }
}
