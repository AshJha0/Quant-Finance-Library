package com.quantfinlib.core;

import com.quantfinlib.internal.Immutable;

import java.util.Arrays;
import java.util.List;

/**
 * Immutable, cache-friendly OHLCV time series backed by primitive arrays
 * (structure-of-arrays layout, no boxing) so indicators read primitive columns directly.
 *
 * <p>The column arrays never leave the object. Indexed accessors such as
 * {@link #close(int)} read one element without allocating; whole columns are
 * available as copies ({@link #closesCopy()}, ...) or copied into a reusable
 * caller buffer with {@link #copyClosesInto(double[], int, int)}.</p>
 *
 * <p>A bar can be flagged <em>synthetic</em>: a placeholder created by
 * alignment (for example {@code data.SeriesAligner.unionForwardFill} carrying
 * the last close across a halt or a listing gap) rather than a bar that
 * traded. Estimators of volatility, average volume and impact should skip
 * synthetic bars, since a flat zero-volume bar shrinks both. Series built
 * without the flag report every bar as real.</p>
 */
@Immutable
public final class BarSeries {

    private final String symbol;
    private final long[] timestamps;
    private final double[] open;
    private final double[] high;
    private final double[] low;
    private final double[] close;
    private final double[] volume;
    private final boolean[] synthetic; // null when no bar is synthetic
    private final int size;

    private BarSeries(String symbol, long[] timestamps, double[] open, double[] high,
                      double[] low, double[] close, double[] volume, boolean[] synthetic, int size) {
        this.symbol = symbol;
        this.timestamps = timestamps;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
        this.synthetic = synthetic;
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

    /** Whether bar {@code i} is an alignment placeholder rather than a traded bar. */
    public boolean isSynthetic(int i) {
        return synthetic != null && synthetic[i];
    }

    /** {@code false} guarantees that no bar is synthetic; {@code true} means some may be. */
    public boolean hasSyntheticBars() {
        return synthetic != null;
    }

    /*
     * The column arrays never leave this object. Per-element reads use the
     * indexed accessors above (no allocation); whole-column reads take a copy
     * once, or copy a range into a caller-owned buffer that can be reused.
     */

    /** A fresh copy of the timestamp column. */
    public long[] timestampsCopy()    { return Arrays.copyOf(timestamps, size); }
    /** A fresh copy of the open column. */
    public double[] opensCopy()       { return Arrays.copyOf(open, size); }
    /** A fresh copy of the high column. */
    public double[] highsCopy()       { return Arrays.copyOf(high, size); }
    /** A fresh copy of the low column. */
    public double[] lowsCopy()        { return Arrays.copyOf(low, size); }
    /** A fresh copy of the close column. */
    public double[] closesCopy()      { return Arrays.copyOf(close, size); }
    /** A fresh copy of the volume column. */
    public double[] volumesCopy()     { return Arrays.copyOf(volume, size); }

    /**
     * Copies closes {@code [from, from + len)} into {@code dst[0, len)} without allocating.
     *
     * @param dst  caller-owned destination, length at least {@code len}
     * @param from first bar index
     * @param len  number of bars
     */
    public void copyClosesInto(double[] dst, int from, int len) {
        System.arraycopy(close, from, dst, 0, len);
    }

    /** Copy of the bar range [from, toExclusive) as a new series (for train/test splits). */
    public BarSeries slice(int from, int toExclusive) {
        if (from < 0 || toExclusive > size || from >= toExclusive) {
            throw new IllegalArgumentException("bad slice [" + from + "," + toExclusive + ") of " + size);
        }
        Builder b = builder(symbol);
        for (int i = from; i < toExclusive; i++) {
            if (isSynthetic(i)) {
                b.addSynthetic(timestamps[i], open[i], high[i], low[i], close[i], volume[i]);
            } else {
                b.add(timestamps[i], open[i], high[i], low[i], close[i], volume[i]);
            }
        }
        return b.build();
    }

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
        private boolean[] syn; // allocated on the first synthetic bar
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

        /** Adds an alignment placeholder bar (see the class doc). */
        public Builder addSynthetic(long timestamp, double open, double high, double low,
                                    double close, double volume) {
            if (syn == null) {
                syn = new boolean[ts.length];
            }
            add(timestamp, open, high, low, close, volume);
            syn[n - 1] = true;
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
            if (syn != null) {
                syn = Arrays.copyOf(syn, cap);
            }
        }

        public BarSeries build() {
            if (n == 0) {
                throw new IllegalStateException("empty series: " + symbol);
            }
            return new BarSeries(symbol,
                    Arrays.copyOf(ts, n), Arrays.copyOf(o, n), Arrays.copyOf(h, n),
                    Arrays.copyOf(l, n), Arrays.copyOf(c, n), Arrays.copyOf(v, n),
                    syn == null ? null : Arrays.copyOf(syn, n), n);
        }
    }
}
