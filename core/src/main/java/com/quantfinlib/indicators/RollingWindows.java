package com.quantfinlib.indicators;

import com.quantfinlib.stats.Numerics;
import com.quantfinlib.stats.Stats;

import static com.quantfinlib.indicators.IndicatorSupport.checkPeriod;

/**
 * Rolling highest, lowest and standard deviation.
 * Package-private implementation behind the {@link Indicators} facade,
 * split out in 3.1 with no change in behaviour.
 */
final class RollingWindows {

    private RollingWindows() {
    }

    static double[] highest(double[] v, int period) {
        checkPeriod(period);
        double[] out = Numerics.nanArray(v.length);
        for (int i = period - 1; i < v.length; i++) {
            double m = Double.NEGATIVE_INFINITY;
            for (int j = i - period + 1; j <= i; j++) {
                m = Math.max(m, v[j]);
            }
            out[i] = m;
        }
        return out;
    }

    static double[] lowest(double[] v, int period) {
        checkPeriod(period);
        double[] out = Numerics.nanArray(v.length);
        for (int i = period - 1; i < v.length; i++) {
            double m = Double.POSITIVE_INFINITY;
            for (int j = i - period + 1; j <= i; j++) {
                m = Math.min(m, v[j]);
            }
            out[i] = m;
        }
        return out;
    }

    static double[] rollingStd(double[] v, int period) {
        checkPeriod(period);
        double[] out = Numerics.nanArray(v.length);
        for (int i = period - 1; i < v.length; i++) {
            out[i] = Stats.populationStdDev(v, i - period + 1, i + 1);
        }
        return out;
    }
}
