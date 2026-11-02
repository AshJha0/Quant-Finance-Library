package com.quantfinlib.indicators;

import com.quantfinlib.core.BarSeries;
import com.quantfinlib.stats.Numerics;
import com.quantfinlib.indicators.Indicators.Ichimoku;
import com.quantfinlib.indicators.Indicators.SuperTrend;

import static com.quantfinlib.indicators.Indicators.atr;
import static com.quantfinlib.indicators.Indicators.highest;
import static com.quantfinlib.indicators.Indicators.lowest;

/**
 * Trend systems: SuperTrend, Ichimoku, parabolic SAR.
 * Package-private implementation behind the {@link Indicators} facade,
 * split out in 3.1 with no change in behaviour.
 */
final class TrendIndicators {

    private TrendIndicators() {
    }

    static SuperTrend superTrend(BarSeries s, int period, double multiplier) {
        int n = s.size();
        double[] a = atr(s, period);
        double[] value = Numerics.nanArray(n);
        int[] dir = new int[n];
        double fUp = Double.NaN, fLo = Double.NaN;
        int trend = 1;
        for (int i = period - 1; i < n; i++) {
            double mid = (s.high(i) + s.low(i)) / 2;
            double bUp = mid + multiplier * a[i];
            double bLo = mid - multiplier * a[i];
            if (Double.isNaN(fUp)) {
                fUp = bUp;
                fLo = bLo;
            } else {
                fUp = (bUp < fUp || s.close(i - 1) > fUp) ? bUp : fUp;
                fLo = (bLo > fLo || s.close(i - 1) < fLo) ? bLo : fLo;
            }
            if (trend == 1 && s.close(i) < fLo) {
                trend = -1;
            } else if (trend == -1 && s.close(i) > fUp) {
                trend = 1;
            }
            dir[i] = trend;
            value[i] = trend == 1 ? fLo : fUp;
        }
        return new SuperTrend(value, dir);
    }

    static Ichimoku ichimoku(BarSeries s, int tenkanPeriod, int kijunPeriod, int senkouBPeriod) {
        int n = s.size();
        double[] tenkan = midChannel(s, tenkanPeriod);
        double[] kijun = midChannel(s, kijunPeriod);
        double[] senkouA = Numerics.nanArray(n);
        double[] senkouB = Numerics.nanArray(n);
        double[] chikou = Numerics.nanArray(n);
        double[] spanBBase = midChannel(s, senkouBPeriod);
        for (int i = 0; i < n; i++) {
            int fwd = i + kijunPeriod;
            if (fwd < n) {
                if (!Double.isNaN(tenkan[i]) && !Double.isNaN(kijun[i])) {
                    senkouA[fwd] = (tenkan[i] + kijun[i]) / 2;
                }
                senkouB[fwd] = spanBBase[i];
            }
            int back = i - kijunPeriod;
            if (back >= 0) {
                chikou[back] = s.close(i);
            }
        }
        return new Ichimoku(tenkan, kijun, senkouA, senkouB, chikou);
    }

    private static double[] midChannel(BarSeries s, int period) {
        double[] hh = highest(s.highsCopy(), period);
        double[] ll = lowest(s.lowsCopy(), period);
        double[] out = Numerics.nanArray(s.size());
        for (int i = 0; i < out.length; i++) {
            if (!Double.isNaN(hh[i])) {
                out[i] = (hh[i] + ll[i]) / 2;
            }
        }
        return out;
    }

    static double[] parabolicSar(BarSeries s, double afStart, double afStep, double afMax) {
        int n = s.size();
        double[] out = Numerics.nanArray(n);
        if (n < 2) {
            return out;
        }
        boolean up = s.close(1) >= s.close(0);
        double sar = up ? Math.min(s.low(0), s.low(1)) : Math.max(s.high(0), s.high(1));
        double ep = up ? Math.max(s.high(0), s.high(1)) : Math.min(s.low(0), s.low(1));
        double af = afStart;
        out[1] = sar;
        for (int i = 2; i < n; i++) {
            sar += af * (ep - sar);
            if (up) {
                sar = Math.min(sar, Math.min(s.low(i - 1), s.low(i - 2)));
                if (s.low(i) < sar) {           // reversal to downtrend
                    up = false;
                    sar = ep;
                    ep = s.low(i);
                    af = afStart;
                } else if (s.high(i) > ep) {
                    ep = s.high(i);
                    af = Math.min(af + afStep, afMax);
                }
            } else {
                sar = Math.max(sar, Math.max(s.high(i - 1), s.high(i - 2)));
                if (s.high(i) > sar) {          // reversal to uptrend
                    up = true;
                    sar = ep;
                    ep = s.high(i);
                    af = afStart;
                } else if (s.low(i) < ep) {
                    ep = s.low(i);
                    af = Math.min(af + afStep, afMax);
                }
            }
            out[i] = sar;
        }
        return out;
    }
}
