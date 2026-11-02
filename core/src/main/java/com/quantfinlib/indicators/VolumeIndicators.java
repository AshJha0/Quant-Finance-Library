package com.quantfinlib.indicators;

import com.quantfinlib.core.BarSeries;
import com.quantfinlib.stats.Numerics;

import static com.quantfinlib.indicators.IndicatorSupport.checkPeriod;

/**
 * Volume indicators: OBV, VWAP, Chaikin money flow.
 * Package-private implementation behind the {@link Indicators} facade,
 * split out in 3.1 with no change in behaviour.
 */
final class VolumeIndicators {

    private VolumeIndicators() {
    }

    static double[] obv(BarSeries s) {
        int n = s.size();
        double[] out = new double[n];
        for (int i = 1; i < n; i++) {
            double d = s.close(i) - s.close(i - 1);
            out[i] = out[i - 1] + (d > 0 ? s.volume(i) : d < 0 ? -s.volume(i) : 0);
        }
        return out;
    }

    static double[] vwap(BarSeries s) {
        int n = s.size();
        double[] out = new double[n];
        double pv = 0, vol = 0;
        for (int i = 0; i < n; i++) {
            double tp = (s.high(i) + s.low(i) + s.close(i)) / 3.0;
            pv += tp * s.volume(i);
            vol += s.volume(i);
            out[i] = vol == 0 ? tp : pv / vol;
        }
        return out;
    }

    static double[] cmf(BarSeries s, int period) {
        checkPeriod(period);
        int n = s.size();
        double[] mfv = new double[n];
        for (int i = 0; i < n; i++) {
            double range = s.high(i) - s.low(i);
            double mult = range == 0 ? 0 : ((s.close(i) - s.low(i)) - (s.high(i) - s.close(i))) / range;
            mfv[i] = mult * s.volume(i);
        }
        double[] out = Numerics.nanArray(n);
        double sumMfv = 0, sumVol = 0;
        for (int i = 0; i < n; i++) {
            sumMfv += mfv[i];
            sumVol += s.volume(i);
            if (i >= period) {
                sumMfv -= mfv[i - period];
                sumVol -= s.volume(i - period);
            }
            if (i >= period - 1) {
                out[i] = sumVol == 0 ? 0 : sumMfv / sumVol;
            }
        }
        return out;
    }
}
