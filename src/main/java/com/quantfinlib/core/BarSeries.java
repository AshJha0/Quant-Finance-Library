package com.quantfinlib.core;

import java.util.Arrays;
import java.util.List;

/**
 * Immutable, cache-friendly OHLCV time series backed by primitive arrays
 * (structure-of-arrays layout, no boxing) for ultra-low-latency computation.
 *
 * <p>Array accessors ({@link #opens()}, {@link #closes()}, ...) return the
 * internal arrays without copying for zero-allocation hot paths. Callers must
 * treat them as read-only.</p>
 */
public final class BarSeries {

    private final String symbol;
    private final long[] timestamps;
    private final double[] open;
    private final double[] high;
    private final double[] low;
    private final double[] close;
    private final double[] volume;
    private final int size;

    private BarSeries(String symbol, long[] timestamps, double[] open, double[] high,
                      double[] low, double[] close, double[] volume, int size) {
        this.symbol = symbol;
        this.timestamps = timestamps;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
        this.size = size;
    }

    public static Builder builder(String symbol) {
        return new Builder(symbol);
    }

    /** Builds a series from close prices only (open = high = low = close). */
    public static BarSeries of(String symbol, double[] closes) {
        Builder b = builder(symbol);
        for (int i = 0; i < closes.length; i++) {
            b.add(i * 86_400_000L, closes[i], closes[i], closes[i], closes[i], 1_000_000);
        }
        return b.build();
    }

    public static BarSeries fromBars(String symbol, List<Bar> bars) {
        Builder b = builder(symbol);
        for (Bar bar : bars) {
            b.add(bar.timestamp(), bar.open(), bar.high(), bar.low(), bar.close(), bar.volume());
        }
        return b.build();
    }

    public String symbol()            { return symbol; }
    public int size()                 { return size; }
    public long timestamp(int i)      { return timestamps[i]; }
    public double open(int i)         { return open[i]; }
    public double high(int i)         { return high[i]; }
    public double low(int i)          { return low[i]; }
    public double close(int i)        { return close[i]; }
    public double volume(int i)       { return volume[i]; }
    public double lastClose()         { return close[size - 1]; }
    public Bar bar(int i)             { return new Bar(timestamps[i], open[i], high[i], low[i], close[i], volume[i]); }

    /** Zero-copy accessors: internal arrays, treat as read-only. */
    public long[] timestamps()        { return timestamps; }
    public double[] opens()           { return open; }
    public double[] highs()           { return high; }
    public double[] lows()            { return low; }
    public double[] closes()          { return close; }
    public double[] volumes()         { return volume; }

    /** Simple (arithmetic) returns; length = size - 1. */
    public double[] returns() {
        double[] r = new double[size - 1];
        for (int i = 1; i < size; i++) {
            r[i - 1] = close[i] / close[i - 1] - 1.0;
        }
        return r;
    }

    /** Log returns; length = size - 1. */
    public double[] logReturns() {
        double[] r = new double[size - 1];
        for (int i = 1; i < size; i++) {
            r[i - 1] = Math.log(close[i] / close[i - 1]);
        }
        return r;
    }

    public static final class Builder {
        private final String symbol;
        private long[] ts = new long[256];
        private double[] o = new double[256];
        private double[] h = new double[256];
        private double[] l = new double[256];
        private double[] c = new double[256];
        private double[] v = new double[256];
        private int n;

        private Builder(String symbol) {
            this.symbol = symbol;
        }

        public Builder add(long timestamp, double open, double high, double low, double close, double volume) {
            if (n == ts.length) {
                grow();
            }
            ts[n] = timestamp; o[n] = open; h[n] = high; l[n] = low; c[n] = close; v[n] = volume;
            n++;
            return this;
        }

        public Builder add(Bar bar) {
            return add(bar.timestamp(), bar.open(), bar.high(), bar.low(), bar.close(), bar.volume());
        }

        private void grow() {
            int cap = ts.length * 2;
            ts = Arrays.copyOf(ts, cap);
            o = Arrays.copyOf(o, cap);
            h = Arrays.copyOf(h, cap);
            l = Arrays.copyOf(l, cap);
            c = Arrays.copyOf(c, cap);
            v = Arrays.copyOf(v, cap);
        }

        public BarSeries build() {
            if (n == 0) {
                throw new IllegalStateException("empty series: " + symbol);
            }
            return new BarSeries(symbol,
                    Arrays.copyOf(ts, n), Arrays.copyOf(o, n), Arrays.copyOf(h, n),
                    Arrays.copyOf(l, n), Arrays.copyOf(c, n), Arrays.copyOf(v, n), n);
        }
    }
}
