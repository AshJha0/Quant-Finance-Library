package com.quantfinlib.ml;

/**
 * Intraday liquidity forecasting: accumulates per-bucket volumes across days
 * into a seasonal profile (e.g. 24 hourly buckets) to predict when liquidity
 * peaks — London open, the London/New York overlap, etc. Feed the profile to
 * {@code VwapScheduler} to align execution with expected volume.
 */
public final class IntradayLiquidityForecaster {

    private final int buckets;
    private final double[] sums;
    private int days;

    /** @param buckets buckets per day (24 = hourly, 48 = half-hourly) */
    public IntradayLiquidityForecaster(int buckets) {
        this.buckets = buckets;
        this.sums = new double[buckets];
    }

    /** Adds one day's observed volume per bucket. */
    public IntradayLiquidityForecaster addDay(double[] volumesPerBucket) {
        if (volumesPerBucket.length != buckets) {
            throw new IllegalArgumentException("expected " + buckets + " buckets");
        }
        for (int i = 0; i < buckets; i++) {
            sums[i] += volumesPerBucket[i];
        }
        days++;
        return this;
    }

    /** Expected volume in a bucket (historical mean). */
    public double forecastVolume(int bucket) {
        return days == 0 ? 0 : sums[bucket] / days;
    }

    /** Normalized profile summing to 1 — directly usable as a VWAP weight curve. */
    public double[] profile() {
        double total = 0;
        for (double s : sums) {
            total += s;
        }
        double[] out = new double[buckets];
        for (int i = 0; i < buckets; i++) {
            out[i] = total == 0 ? 1.0 / buckets : sums[i] / total;
        }
        return out;
    }

    /** Bucket with the highest expected liquidity. */
    public int peakBucket() {
        int best = 0;
        for (int i = 1; i < buckets; i++) {
            if (sums[i] > sums[best]) {
                best = i;
            }
        }
        return best;
    }

    /** Share of daily liquidity expected within [fromBucket, toBucket). */
    public double sessionShare(int fromBucket, int toBucket) {
        double[] p = profile();
        double share = 0;
        for (int i = fromBucket; i < toBucket; i++) {
            share += p[i];
        }
        return share;
    }

    /** FX session label for an hour of day in UTC. */
    public static String fxSession(int hourUtc) {
        if (hourUtc >= 22) {
            return "SYDNEY";
        }
        if (hourUtc < 7) {
            return "TOKYO";
        }
        if (hourUtc < 12) {
            return "LONDON";
        }
        if (hourUtc < 17) {
            return "LONDON_NY_OVERLAP";
        }
        return "NEW_YORK";
    }
}
