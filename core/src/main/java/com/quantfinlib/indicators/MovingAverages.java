package com.quantfinlib.indicators;

import com.quantfinlib.stats.Numerics;

import static com.quantfinlib.indicators.IndicatorSupport.checkPeriod;

/**
 * Simple, exponential and weighted moving averages.
 * Package-private implementation behind the {@link Indicators} facade,
 * split out in 3.1 with no change in behaviour.
 */
final class MovingAverages {

    private MovingAverages() {
    }

    static double[] sma(double[] v, int period) {
        checkPeriod(period);
        double[] out = Numerics.nanArray(v.length);
        if (v.length < period) {
            return out;
        }
        double sum = 0;
        for (int i = 0; i < v.length; i++) {
            sum += v[i];
            if (i >= period) {
                sum -= v[i - period];
            }
            if ((i + 1) % period == 0) {
                sum = 0;
                for (int j = i - period + 1; j <= i; j++) {
                    sum += v[j];
                }
            }
            if (i >= period - 1) {
                out[i] = sum / period;
            }
        }
        return out;
    }

    static double[] ema(double[] v, int period) {
        checkPeriod(period);
        double[] out = Numerics.nanArray(v.length);
        if (v.length < period) {
            return out;
        }
        double sum = 0;
        for (int i = 0; i < period; i++) {
            sum += v[i];
        }
        double prev = sum / period;
        out[period - 1] = prev;
        double k = 2.0 / (period + 1);
        for (int i = period; i < v.length; i++) {
            prev += (v[i] - prev) * k;
            out[i] = prev;
        }
        return out;
    }

    static double[] wma(double[] v, int period) {
        checkPeriod(period);
        double[] out = Numerics.nanArray(v.length);
        double denom = period * (period + 1) / 2.0;
        for (int i = period - 1; i < v.length; i++) {
            double s = 0;
            for (int j = 0; j < period; j++) {
                s += v[i - j] * (period - j);
            }
            out[i] = s / denom;
        }
        return out;
    }
}
