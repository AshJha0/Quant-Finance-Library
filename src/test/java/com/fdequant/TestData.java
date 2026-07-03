package com.fdequant;

import com.fdequant.core.BarSeries;

import java.util.SplittableRandom;

/** Deterministic synthetic data generators shared by the test suite. */
public final class TestData {

    private TestData() {
    }

    /** GBM daily series with OHLC structure; deterministic for a given seed. */
    public static BarSeries gbmSeries(String symbol, int days, double startPrice,
                                      double annualDrift, double annualVol, long seed) {
        SplittableRandom rnd = new SplittableRandom(seed);
        double dt = 1.0 / 252;
        double price = startPrice;
        BarSeries.Builder b = BarSeries.builder(symbol);
        for (int d = 0; d < days; d++) {
            double z = rnd.nextGaussian();
            double next = price * Math.exp((annualDrift - 0.5 * annualVol * annualVol) * dt
                    + annualVol * Math.sqrt(dt) * z);
            double high = Math.max(price, next) * (1 + rnd.nextDouble() * 0.004);
            double low = Math.min(price, next) * (1 - rnd.nextDouble() * 0.004);
            b.add(d * 86_400_000L, price, high, low, next, 1_000_000 * (0.5 + rnd.nextDouble()));
            price = next;
        }
        return b.build();
    }

    /** Deterministic trending sine-wave close series (no randomness). */
    public static double[] sineTrend(int n, double base, double trendPerBar, double amplitude, int period) {
        double[] v = new double[n];
        for (int i = 0; i < n; i++) {
            v[i] = base + trendPerBar * i + amplitude * Math.sin(2 * Math.PI * i / period);
        }
        return v;
    }
}
