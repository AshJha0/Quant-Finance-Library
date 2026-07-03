package com.quantfinlib.execution;

import java.util.List;
import java.util.SplittableRandom;

/**
 * TWAP (time-weighted average price) schedule design: splits a parent order
 * into evenly spaced child slices, optionally with randomized sizes to reduce
 * schedule predictability (anti-gaming). Slice quantities always sum exactly
 * to the parent quantity.
 */
public final class TwapScheduler {

    private TwapScheduler() {
    }

    /** Equal slices at equal intervals starting at t=0. */
    public static List<Slice> schedule(long totalQty, long durationMillis, int numSlices) {
        double[] weights = new double[numSlices];
        java.util.Arrays.fill(weights, 1.0);
        return toSlices(totalQty, durationMillis, weights);
    }

    /**
     * Randomized TWAP: slice sizes jittered by up to {@code jitterPct}
     * (e.g. 0.3 = ±30%), deterministic for a given seed.
     */
    public static List<Slice> scheduleRandomized(long totalQty, long durationMillis, int numSlices,
                                                 double jitterPct, long seed) {
        SplittableRandom rnd = new SplittableRandom(seed);
        double[] weights = new double[numSlices];
        for (int i = 0; i < numSlices; i++) {
            weights[i] = 1 + jitterPct * (2 * rnd.nextDouble() - 1);
        }
        return toSlices(totalQty, durationMillis, weights);
    }

    static List<Slice> toSlices(long totalQty, long durationMillis, double[] weights) {
        if (weights.length == 0 || totalQty <= 0) {
            throw new IllegalArgumentException("need positive quantity and at least one slice");
        }
        long[] quantities = VwapScheduler.allocateProportionally(totalQty, weights);
        List<Slice> out = new java.util.ArrayList<>(weights.length);
        for (int i = 0; i < weights.length; i++) {
            out.add(new Slice(durationMillis * i / weights.length, quantities[i]));
        }
        return out;
    }
}
