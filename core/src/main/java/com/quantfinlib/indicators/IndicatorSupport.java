package com.quantfinlib.indicators;

import com.quantfinlib.stats.Numerics;

/**
 * Shared argument checks and smoothing helpers for the indicator implementations.
 * Package-private implementation behind the {@link Indicators} facade,
 * split out in 3.1 with no change in behaviour.
 */
final class IndicatorSupport {

    private IndicatorSupport() {
    }

    static double[] smoothIgnoringNan(double[] v, int period) {
        if (period <= 1) {
            return v.clone();
        }
        double[] out = Numerics.nanArray(v.length);
        for (int i = 0; i < v.length; i++) {
            double s = 0;
            int cnt = 0;
            for (int j = Math.max(0, i - period + 1); j <= i; j++) {
                if (!Double.isNaN(v[j])) {
                    s += v[j];
                    cnt++;
                }
            }
            if (cnt == period) {
                out[i] = s / period;
            }
        }
        return out;
    }

    static void checkPeriod(int period) {
        if (period < 1) {
            throw new IllegalArgumentException("period must be >= 1: " + period);
        }
    }
}
