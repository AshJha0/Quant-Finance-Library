package com.quantfinlib.execution;

import java.util.ArrayList;
import java.util.List;

/**
 * VWAP schedule design: allocates child slices proportionally to an expected
 * intraday volume profile (e.g. from
 * {@code com.quantfinlib.ml.IntradayLiquidityForecaster}), so participation
 * tracks the market's own volume curve.
 */
public final class VwapScheduler {

    private VwapScheduler() {
    }

    /**
     * @param totalQty       parent order quantity
     * @param volumeProfile  expected volume per bucket (any positive scale)
     * @param durationMillis total execution window; slice i starts at
     *                       {@code i * duration / buckets}
     */
    public static List<Slice> schedule(long totalQty, double[] volumeProfile, long durationMillis) {
        if (volumeProfile.length == 0 || totalQty <= 0) {
            throw new IllegalArgumentException("need positive quantity and a non-empty profile");
        }
        long[] quantities = allocateProportionally(totalQty, volumeProfile);
        List<Slice> out = new ArrayList<>(volumeProfile.length);
        for (int i = 0; i < volumeProfile.length; i++) {
            out.add(new Slice(durationMillis * i / volumeProfile.length, quantities[i]));
        }
        return out;
    }

    /**
     * Largest-remainder proportional allocation: integer quantities that sum
     * exactly to {@code total}, proportional to {@code weights}.
     */
    public static long[] allocateProportionally(long total, double[] weights) {
        int n = weights.length;
        double sum = 0;
        for (double w : weights) {
            if (w < 0) {
                throw new IllegalArgumentException("negative weight: " + w);
            }
            sum += w;
        }
        long[] out = new long[n];
        if (sum == 0) {
            out[0] = total;
            return out;
        }
        double[] fractions = new double[n];
        long allocated = 0;
        for (int i = 0; i < n; i++) {
            double raw = total * weights[i] / sum;
            out[i] = (long) Math.floor(raw);
            fractions[i] = raw - out[i];
            allocated += out[i];
        }
        // Distribute the remainder to the largest fractional parts.
        long remainder = total - allocated;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> Double.compare(fractions[b], fractions[a]));
        for (int k = 0; k < remainder; k++) {
            out[order[(int) (k % n)]]++;
        }
        return out;
    }
}
