package com.quantfinlib.indicators;

import com.quantfinlib.core.BarSeries;
import com.quantfinlib.stats.Numerics;
import com.quantfinlib.stats.Stats;

import java.util.Arrays;

/**
 * Technical Indicator Engine: implementations of the standard
 * technical analysis toolkit. All indicators operate on primitive arrays and
 * return primitive arrays (or small records of arrays) aligned with the input:
 * index i of the output corresponds to bar i, with {@code NaN} for warm-up bars.
 *
 * <p>Included: RSI, SMA, EMA, WMA, VWAP, MACD, ATR, ADX, CCI, ROC, Momentum,
 * OBV, CMF, SuperTrend, Ichimoku Cloud, Stochastic RSI, Williams %R,
 * Parabolic SAR, Bollinger Bands, Keltner Channel, Donchian Channel.</p>
 */
public final class Indicators {

    private Indicators() {
    }

    // ------------------------------------------------------------------
    // Result records for multi-line indicators
    // ------------------------------------------------------------------

    public static final class Macd {
        private final double[] line;
        private final double[] signal;
        private final double[] histogram;

        /**
         * Creates the value; array and collection arguments are copied.
         */
        public Macd(double[] line, double[] signal, double[] histogram) {
            this.line = line == null ? null : line.clone();
            this.signal = signal == null ? null : signal.clone();
            this.histogram = histogram == null ? null : histogram.clone();
        }

        /** Element {@code i} of {@code line}. */
        public double line(int i) {
            return line[i];
        }

        /** Length of {@code line}. */
        public int lineLength() {
            return line.length;
        }

        /** A fresh copy of {@code line}. */
        public double[] lineCopy() {
            return line == null ? null : line.clone();
        }

        /** Element {@code i} of {@code signal}. */
        public double signal(int i) {
            return signal[i];
        }

        /** Length of {@code signal}. */
        public int signalLength() {
            return signal.length;
        }

        /** A fresh copy of {@code signal}. */
        public double[] signalCopy() {
            return signal == null ? null : signal.clone();
        }

        /** Element {@code i} of {@code histogram}. */
        public double histogram(int i) {
            return histogram[i];
        }

        /** Length of {@code histogram}. */
        public int histogramLength() {
            return histogram.length;
        }

        /** A fresh copy of {@code histogram}. */
        public double[] histogramCopy() {
            return histogram == null ? null : histogram.clone();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Macd o)) {
                return false;
            }
            return java.util.Arrays.equals(line, o.line)
                    && java.util.Arrays.equals(signal, o.signal)
                    && java.util.Arrays.equals(histogram, o.histogram);
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Arrays.hashCode(line);
            h = 31 * h + java.util.Arrays.hashCode(signal);
            h = 31 * h + java.util.Arrays.hashCode(histogram);
            return h;
        }

        @Override
        public String toString() {
            return "Macd[" + "line=" + java.util.Arrays.toString(line) + ", " + "signal=" + java.util.Arrays.toString(signal) + ", " + "histogram=" + java.util.Arrays.toString(histogram) + "]";
        }
    }

    public static final class Bollinger {
        private final double[] upper;
        private final double[] middle;
        private final double[] lower;

        /**
         * Creates the value; array and collection arguments are copied.
         */
        public Bollinger(double[] upper, double[] middle, double[] lower) {
            this.upper = upper == null ? null : upper.clone();
            this.middle = middle == null ? null : middle.clone();
            this.lower = lower == null ? null : lower.clone();
        }

        /** Element {@code i} of {@code upper}. */
        public double upper(int i) {
            return upper[i];
        }

        /** Length of {@code upper}. */
        public int upperLength() {
            return upper.length;
        }

        /** A fresh copy of {@code upper}. */
        public double[] upperCopy() {
            return upper == null ? null : upper.clone();
        }

        /** Element {@code i} of {@code middle}. */
        public double middle(int i) {
            return middle[i];
        }

        /** Length of {@code middle}. */
        public int middleLength() {
            return middle.length;
        }

        /** A fresh copy of {@code middle}. */
        public double[] middleCopy() {
            return middle == null ? null : middle.clone();
        }

        /** Element {@code i} of {@code lower}. */
        public double lower(int i) {
            return lower[i];
        }

        /** Length of {@code lower}. */
        public int lowerLength() {
            return lower.length;
        }

        /** A fresh copy of {@code lower}. */
        public double[] lowerCopy() {
            return lower == null ? null : lower.clone();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Bollinger o)) {
                return false;
            }
            return java.util.Arrays.equals(upper, o.upper)
                    && java.util.Arrays.equals(middle, o.middle)
                    && java.util.Arrays.equals(lower, o.lower);
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Arrays.hashCode(upper);
            h = 31 * h + java.util.Arrays.hashCode(middle);
            h = 31 * h + java.util.Arrays.hashCode(lower);
            return h;
        }

        @Override
        public String toString() {
            return "Bollinger[" + "upper=" + java.util.Arrays.toString(upper) + ", " + "middle=" + java.util.Arrays.toString(middle) + ", " + "lower=" + java.util.Arrays.toString(lower) + "]";
        }
    }

    public static final class Adx {
        private final double[] adx;
        private final double[] plusDi;
        private final double[] minusDi;

        /**
         * Creates the value; array and collection arguments are copied.
         */
        public Adx(double[] adx, double[] plusDi, double[] minusDi) {
            this.adx = adx == null ? null : adx.clone();
            this.plusDi = plusDi == null ? null : plusDi.clone();
            this.minusDi = minusDi == null ? null : minusDi.clone();
        }

        /** Element {@code i} of {@code adx}. */
        public double adx(int i) {
            return adx[i];
        }

        /** Length of {@code adx}. */
        public int adxLength() {
            return adx.length;
        }

        /** A fresh copy of {@code adx}. */
        public double[] adxCopy() {
            return adx == null ? null : adx.clone();
        }

        /** Element {@code i} of {@code plusDi}. */
        public double plusDi(int i) {
            return plusDi[i];
        }

        /** Length of {@code plusDi}. */
        public int plusDiLength() {
            return plusDi.length;
        }

        /** A fresh copy of {@code plusDi}. */
        public double[] plusDiCopy() {
            return plusDi == null ? null : plusDi.clone();
        }

        /** Element {@code i} of {@code minusDi}. */
        public double minusDi(int i) {
            return minusDi[i];
        }

        /** Length of {@code minusDi}. */
        public int minusDiLength() {
            return minusDi.length;
        }

        /** A fresh copy of {@code minusDi}. */
        public double[] minusDiCopy() {
            return minusDi == null ? null : minusDi.clone();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Adx o)) {
                return false;
            }
            return java.util.Arrays.equals(adx, o.adx)
                    && java.util.Arrays.equals(plusDi, o.plusDi)
                    && java.util.Arrays.equals(minusDi, o.minusDi);
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Arrays.hashCode(adx);
            h = 31 * h + java.util.Arrays.hashCode(plusDi);
            h = 31 * h + java.util.Arrays.hashCode(minusDi);
            return h;
        }

        @Override
        public String toString() {
            return "Adx[" + "adx=" + java.util.Arrays.toString(adx) + ", " + "plusDi=" + java.util.Arrays.toString(plusDi) + ", " + "minusDi=" + java.util.Arrays.toString(minusDi) + "]";
        }
    }

    /** direction: +1 = uptrend (value is support), -1 = downtrend (value is resistance). */
    public static final class SuperTrend {
        private final double[] value;
        private final int[] direction;

        /**
         * Creates the value; array and collection arguments are copied.
         */
        public SuperTrend(double[] value, int[] direction) {
            this.value = value == null ? null : value.clone();
            this.direction = direction == null ? null : direction.clone();
        }

        /** Element {@code i} of {@code value}. */
        public double value(int i) {
            return value[i];
        }

        /** Length of {@code value}. */
        public int valueLength() {
            return value.length;
        }

        /** A fresh copy of {@code value}. */
        public double[] valueCopy() {
            return value == null ? null : value.clone();
        }

        /** Element {@code i} of {@code direction}. */
        public int direction(int i) {
            return direction[i];
        }

        /** Length of {@code direction}. */
        public int directionLength() {
            return direction.length;
        }

        /** A fresh copy of {@code direction}. */
        public int[] directionCopy() {
            return direction == null ? null : direction.clone();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof SuperTrend o)) {
                return false;
            }
            return java.util.Arrays.equals(value, o.value)
                    && java.util.Arrays.equals(direction, o.direction);
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Arrays.hashCode(value);
            h = 31 * h + java.util.Arrays.hashCode(direction);
            return h;
        }

        @Override
        public String toString() {
            return "SuperTrend[" + "value=" + java.util.Arrays.toString(value) + ", " + "direction=" + java.util.Arrays.toString(direction) + "]";
        }
    }

    /**
     * Ichimoku lines. {@code chikou} is for display only: {@code chikou[i]}
     * holds {@code close(i + kijun)}, a price from the future relative to
     * bar {@code i}. Any rule or signal reading it at bar {@code i} looks
     * ahead. The causal form of the lagging-span signal is
     * {@code close(i)} compared with {@code close(i − kijun)}, which
     * strategy rules should read from the close array directly.
     */
    public static final class Ichimoku {
        private final double[] tenkan;
        private final double[] kijun;
        private final double[] senkouA;
        private final double[] senkouB;
        private final double[] chikou;

        /**
         * Creates the value; array and collection arguments are copied.
         */
        public Ichimoku(double[] tenkan, double[] kijun, double[] senkouA, double[] senkouB,
                double[] chikou) {
            this.tenkan = tenkan == null ? null : tenkan.clone();
            this.kijun = kijun == null ? null : kijun.clone();
            this.senkouA = senkouA == null ? null : senkouA.clone();
            this.senkouB = senkouB == null ? null : senkouB.clone();
            this.chikou = chikou == null ? null : chikou.clone();
        }

        /** Element {@code i} of {@code tenkan}. */
        public double tenkan(int i) {
            return tenkan[i];
        }

        /** Length of {@code tenkan}. */
        public int tenkanLength() {
            return tenkan.length;
        }

        /** A fresh copy of {@code tenkan}. */
        public double[] tenkanCopy() {
            return tenkan == null ? null : tenkan.clone();
        }

        /** Element {@code i} of {@code kijun}. */
        public double kijun(int i) {
            return kijun[i];
        }

        /** Length of {@code kijun}. */
        public int kijunLength() {
            return kijun.length;
        }

        /** A fresh copy of {@code kijun}. */
        public double[] kijunCopy() {
            return kijun == null ? null : kijun.clone();
        }

        /** Element {@code i} of {@code senkouA}. */
        public double senkouA(int i) {
            return senkouA[i];
        }

        /** Length of {@code senkouA}. */
        public int senkouALength() {
            return senkouA.length;
        }

        /** A fresh copy of {@code senkouA}. */
        public double[] senkouACopy() {
            return senkouA == null ? null : senkouA.clone();
        }

        /** Element {@code i} of {@code senkouB}. */
        public double senkouB(int i) {
            return senkouB[i];
        }

        /** Length of {@code senkouB}. */
        public int senkouBLength() {
            return senkouB.length;
        }

        /** A fresh copy of {@code senkouB}. */
        public double[] senkouBCopy() {
            return senkouB == null ? null : senkouB.clone();
        }

        /** Element {@code i} of {@code chikou}. */
        public double chikou(int i) {
            return chikou[i];
        }

        /** Length of {@code chikou}. */
        public int chikouLength() {
            return chikou.length;
        }

        /** A fresh copy of {@code chikou}. */
        public double[] chikouCopy() {
            return chikou == null ? null : chikou.clone();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Ichimoku o)) {
                return false;
            }
            return java.util.Arrays.equals(tenkan, o.tenkan)
                    && java.util.Arrays.equals(kijun, o.kijun)
                    && java.util.Arrays.equals(senkouA, o.senkouA)
                    && java.util.Arrays.equals(senkouB, o.senkouB)
                    && java.util.Arrays.equals(chikou, o.chikou);
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Arrays.hashCode(tenkan);
            h = 31 * h + java.util.Arrays.hashCode(kijun);
            h = 31 * h + java.util.Arrays.hashCode(senkouA);
            h = 31 * h + java.util.Arrays.hashCode(senkouB);
            h = 31 * h + java.util.Arrays.hashCode(chikou);
            return h;
        }

        @Override
        public String toString() {
            return "Ichimoku[" + "tenkan=" + java.util.Arrays.toString(tenkan) + ", " + "kijun=" + java.util.Arrays.toString(kijun) + ", " + "senkouA=" + java.util.Arrays.toString(senkouA) + ", " + "senkouB=" + java.util.Arrays.toString(senkouB) + ", " + "chikou=" + java.util.Arrays.toString(chikou) + "]";
        }
    }

    public static final class StochRsi {
        private final double[] k;
        private final double[] d;

        /**
         * Creates the value; array and collection arguments are copied.
         */
        public StochRsi(double[] k, double[] d) {
            this.k = k == null ? null : k.clone();
            this.d = d == null ? null : d.clone();
        }

        /** Element {@code i} of {@code k}. */
        public double k(int i) {
            return k[i];
        }

        /** Length of {@code k}. */
        public int kLength() {
            return k.length;
        }

        /** A fresh copy of {@code k}. */
        public double[] kCopy() {
            return k == null ? null : k.clone();
        }

        /** Element {@code i} of {@code d}. */
        public double d(int i) {
            return d[i];
        }

        /** Length of {@code d}. */
        public int dLength() {
            return d.length;
        }

        /** A fresh copy of {@code d}. */
        public double[] dCopy() {
            return d == null ? null : d.clone();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof StochRsi o)) {
                return false;
            }
            return java.util.Arrays.equals(k, o.k)
                    && java.util.Arrays.equals(d, o.d);
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Arrays.hashCode(k);
            h = 31 * h + java.util.Arrays.hashCode(d);
            return h;
        }

        @Override
        public String toString() {
            return "StochRsi[" + "k=" + java.util.Arrays.toString(k) + ", " + "d=" + java.util.Arrays.toString(d) + "]";
        }
    }

    public static final class Keltner {
        private final double[] upper;
        private final double[] middle;
        private final double[] lower;

        /**
         * Creates the value; array and collection arguments are copied.
         */
        public Keltner(double[] upper, double[] middle, double[] lower) {
            this.upper = upper == null ? null : upper.clone();
            this.middle = middle == null ? null : middle.clone();
            this.lower = lower == null ? null : lower.clone();
        }

        /** Element {@code i} of {@code upper}. */
        public double upper(int i) {
            return upper[i];
        }

        /** Length of {@code upper}. */
        public int upperLength() {
            return upper.length;
        }

        /** A fresh copy of {@code upper}. */
        public double[] upperCopy() {
            return upper == null ? null : upper.clone();
        }

        /** Element {@code i} of {@code middle}. */
        public double middle(int i) {
            return middle[i];
        }

        /** Length of {@code middle}. */
        public int middleLength() {
            return middle.length;
        }

        /** A fresh copy of {@code middle}. */
        public double[] middleCopy() {
            return middle == null ? null : middle.clone();
        }

        /** Element {@code i} of {@code lower}. */
        public double lower(int i) {
            return lower[i];
        }

        /** Length of {@code lower}. */
        public int lowerLength() {
            return lower.length;
        }

        /** A fresh copy of {@code lower}. */
        public double[] lowerCopy() {
            return lower == null ? null : lower.clone();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Keltner o)) {
                return false;
            }
            return java.util.Arrays.equals(upper, o.upper)
                    && java.util.Arrays.equals(middle, o.middle)
                    && java.util.Arrays.equals(lower, o.lower);
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Arrays.hashCode(upper);
            h = 31 * h + java.util.Arrays.hashCode(middle);
            h = 31 * h + java.util.Arrays.hashCode(lower);
            return h;
        }

        @Override
        public String toString() {
            return "Keltner[" + "upper=" + java.util.Arrays.toString(upper) + ", " + "middle=" + java.util.Arrays.toString(middle) + ", " + "lower=" + java.util.Arrays.toString(lower) + "]";
        }
    }

    public static final class Donchian {
        private final double[] upper;
        private final double[] middle;
        private final double[] lower;

        /**
         * Creates the value; array and collection arguments are copied.
         */
        public Donchian(double[] upper, double[] middle, double[] lower) {
            this.upper = upper == null ? null : upper.clone();
            this.middle = middle == null ? null : middle.clone();
            this.lower = lower == null ? null : lower.clone();
        }

        /** Element {@code i} of {@code upper}. */
        public double upper(int i) {
            return upper[i];
        }

        /** Length of {@code upper}. */
        public int upperLength() {
            return upper.length;
        }

        /** A fresh copy of {@code upper}. */
        public double[] upperCopy() {
            return upper == null ? null : upper.clone();
        }

        /** Element {@code i} of {@code middle}. */
        public double middle(int i) {
            return middle[i];
        }

        /** Length of {@code middle}. */
        public int middleLength() {
            return middle.length;
        }

        /** A fresh copy of {@code middle}. */
        public double[] middleCopy() {
            return middle == null ? null : middle.clone();
        }

        /** Element {@code i} of {@code lower}. */
        public double lower(int i) {
            return lower[i];
        }

        /** Length of {@code lower}. */
        public int lowerLength() {
            return lower.length;
        }

        /** A fresh copy of {@code lower}. */
        public double[] lowerCopy() {
            return lower == null ? null : lower.clone();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Donchian o)) {
                return false;
            }
            return java.util.Arrays.equals(upper, o.upper)
                    && java.util.Arrays.equals(middle, o.middle)
                    && java.util.Arrays.equals(lower, o.lower);
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Arrays.hashCode(upper);
            h = 31 * h + java.util.Arrays.hashCode(middle);
            h = 31 * h + java.util.Arrays.hashCode(lower);
            return h;
        }

        @Override
        public String toString() {
            return "Donchian[" + "upper=" + java.util.Arrays.toString(upper) + ", " + "middle=" + java.util.Arrays.toString(middle) + ", " + "lower=" + java.util.Arrays.toString(lower) + "]";
        }
    }

    // ------------------------------------------------------------------
    // Moving averages
    // ------------------------------------------------------------------

    /**
     * Simple moving average. Rolling sum, re-summed exactly from the window
     * every {@code period} bars so rounding error from the add/subtract
     * updates cannot accumulate over long series (the same schedule as
     * {@link StreamingIndicators.Sma}, so the two stay bit-identical).
     */
    public static double[] sma(double[] v, int period) {
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

    /** Exponential moving average, seeded with the SMA of the first {@code period} values. */
    public static double[] ema(double[] v, int period) {
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

    /** Linearly weighted moving average. */
    public static double[] wma(double[] v, int period) {
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

    // ------------------------------------------------------------------
    // Momentum / oscillators
    // ------------------------------------------------------------------

    /** Relative Strength Index (Wilder smoothing). */
    public static double[] rsi(double[] v, int period) {
        checkPeriod(period);
        double[] out = Numerics.nanArray(v.length);
        if (v.length <= period) {
            return out;
        }
        double gain = 0, loss = 0;
        for (int i = 1; i <= period; i++) {
            double d = v[i] - v[i - 1];
            if (d > 0) {
                gain += d;
            } else {
                loss -= d;
            }
        }
        double avgGain = gain / period, avgLoss = loss / period;
        out[period] = toRsi(avgGain, avgLoss);
        for (int i = period + 1; i < v.length; i++) {
            double d = v[i] - v[i - 1];
            avgGain = (avgGain * (period - 1) + Math.max(d, 0)) / period;
            avgLoss = (avgLoss * (period - 1) + Math.max(-d, 0)) / period;
            out[i] = toRsi(avgGain, avgLoss);
        }
        return out;
    }

    private static double toRsi(double avgGain, double avgLoss) {
        if (avgLoss == 0) {
            return avgGain == 0 ? 50 : 100;
        }
        return 100 - 100 / (1 + avgGain / avgLoss);
    }

    /** Rate of change, percent: (v[i] / v[i-period] - 1) * 100. */
    public static double[] roc(double[] v, int period) {
        checkPeriod(period);
        double[] out = Numerics.nanArray(v.length);
        for (int i = period; i < v.length; i++) {
            out[i] = (v[i] / v[i - period] - 1) * 100;
        }
        return out;
    }

    /** Momentum: v[i] - v[i-period]. */
    public static double[] momentum(double[] v, int period) {
        checkPeriod(period);
        double[] out = Numerics.nanArray(v.length);
        for (int i = period; i < v.length; i++) {
            out[i] = v[i] - v[i - period];
        }
        return out;
    }

    /** MACD: EMA(fast) - EMA(slow), with an EMA signal line and histogram. */
    public static Macd macd(double[] close, int fastPeriod, int slowPeriod, int signalPeriod) {
        double[] fast = ema(close, fastPeriod);
        double[] slow = ema(close, slowPeriod);
        int n = close.length;
        double[] line = Numerics.nanArray(n);
        for (int i = 0; i < n; i++) {
            line[i] = fast[i] - slow[i];
        }
        double[] signal = Numerics.nanArray(n);
        double[] hist = Numerics.nanArray(n);
        int start = slowPeriod - 1;
        if (start < n) {
            double[] valid = Arrays.copyOfRange(line, start, n);
            double[] sigValid = ema(valid, signalPeriod);
            for (int i = 0; i < sigValid.length; i++) {
                signal[start + i] = sigValid[i];
                hist[start + i] = line[start + i] - sigValid[i];
            }
        }
        return new Macd(line, signal, hist);
    }

    /** Stochastic RSI: stochastic oscillator applied to RSI, with %K and %D smoothing. */
    public static StochRsi stochasticRsi(double[] close, int rsiPeriod, int stochPeriod,
                                         int kSmooth, int dSmooth) {
        double[] r = rsi(close, rsiPeriod);
        int n = close.length;
        double[] raw = Numerics.nanArray(n);
        for (int i = rsiPeriod + stochPeriod - 1; i < n; i++) {
            double hi = Double.NEGATIVE_INFINITY, lo = Double.POSITIVE_INFINITY;
            for (int j = i - stochPeriod + 1; j <= i; j++) {
                if (!Double.isNaN(r[j])) {
                    hi = Math.max(hi, r[j]);
                    lo = Math.min(lo, r[j]);
                }
            }
            raw[i] = hi > lo ? (r[i] - lo) / (hi - lo) * 100 : 50;
        }
        double[] k = smoothIgnoringNan(raw, kSmooth);
        double[] d = smoothIgnoringNan(k, dSmooth);
        return new StochRsi(k, d);
    }

    /** Williams %R: -100 * (highestHigh - close) / (highestHigh - lowestLow). */
    public static double[] williamsR(BarSeries s, int period) {
        checkPeriod(period);
        int n = s.size();
        double[] out = Numerics.nanArray(n);
        for (int i = period - 1; i < n; i++) {
            double hh = Double.NEGATIVE_INFINITY, ll = Double.POSITIVE_INFINITY;
            for (int j = i - period + 1; j <= i; j++) {
                hh = Math.max(hh, s.high(j));
                ll = Math.min(ll, s.low(j));
            }
            out[i] = hh > ll ? -100 * (hh - s.close(i)) / (hh - ll) : -50;
        }
        return out;
    }

    /** Commodity Channel Index over typical price. */
    public static double[] cci(BarSeries s, int period) {
        checkPeriod(period);
        int n = s.size();
        double[] tp = new double[n];
        for (int i = 0; i < n; i++) {
            tp[i] = (s.high(i) + s.low(i) + s.close(i)) / 3.0;
        }
        double[] out = Numerics.nanArray(n);
        for (int i = period - 1; i < n; i++) {
            double m = Stats.mean(tp, i - period + 1, i + 1);
            double dev = 0;
            for (int j = i - period + 1; j <= i; j++) {
                dev += Math.abs(tp[j] - m);
            }
            dev /= period;
            out[i] = dev == 0 ? 0 : (tp[i] - m) / (0.015 * dev);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Volatility / range
    // ------------------------------------------------------------------

    /** True range series. */
    public static double[] trueRange(BarSeries s) {
        int n = s.size();
        double[] tr = new double[n];
        tr[0] = s.high(0) - s.low(0);
        for (int i = 1; i < n; i++) {
            double hl = s.high(i) - s.low(i);
            double hc = Math.abs(s.high(i) - s.close(i - 1));
            double lc = Math.abs(s.low(i) - s.close(i - 1));
            tr[i] = Math.max(hl, Math.max(hc, lc));
        }
        return tr;
    }

    /** Average True Range (Wilder smoothing). */
    public static double[] atr(BarSeries s, int period) {
        checkPeriod(period);
        double[] tr = trueRange(s);
        int n = tr.length;
        double[] out = Numerics.nanArray(n);
        if (n < period) {
            return out;
        }
        double sum = 0;
        for (int i = 0; i < period; i++) {
            sum += tr[i];
        }
        double prev = sum / period;
        out[period - 1] = prev;
        for (int i = period; i < n; i++) {
            prev = (prev * (period - 1) + tr[i]) / period;
            out[i] = prev;
        }
        return out;
    }

    /** Average Directional Index with +DI / -DI (Wilder). */
    public static Adx adx(BarSeries s, int period) {
        checkPeriod(period);
        int n = s.size();
        double[] adx = Numerics.nanArray(n);
        double[] plusDi = Numerics.nanArray(n);
        double[] minusDi = Numerics.nanArray(n);
        if (n <= 2 * period) {
            return new Adx(adx, plusDi, minusDi);
        }
        double[] tr = trueRange(s);
        double smTr = 0, smPlus = 0, smMinus = 0;
        for (int i = 1; i <= period; i++) {
            smTr += tr[i];
            double up = s.high(i) - s.high(i - 1);
            double dn = s.low(i - 1) - s.low(i);
            smPlus += (up > dn && up > 0) ? up : 0;
            smMinus += (dn > up && dn > 0) ? dn : 0;
        }
        double[] dx = Numerics.nanArray(n);
        for (int i = period; i < n; i++) {
            if (i > period) {
                double up = s.high(i) - s.high(i - 1);
                double dn = s.low(i - 1) - s.low(i);
                smTr = smTr - smTr / period + tr[i];
                smPlus = smPlus - smPlus / period + ((up > dn && up > 0) ? up : 0);
                smMinus = smMinus - smMinus / period + ((dn > up && dn > 0) ? dn : 0);
            }
            double pdi = smTr == 0 ? 0 : 100 * smPlus / smTr;
            double mdi = smTr == 0 ? 0 : 100 * smMinus / smTr;
            plusDi[i] = pdi;
            minusDi[i] = mdi;
            double sum = pdi + mdi;
            dx[i] = sum == 0 ? 0 : 100 * Math.abs(pdi - mdi) / sum;
        }
        // ADX = Wilder average of DX
        double acc = 0;
        for (int i = period; i < 2 * period; i++) {
            acc += dx[i];
        }
        double prev = acc / period;
        adx[2 * period - 1] = prev;
        for (int i = 2 * period; i < n; i++) {
            prev = (prev * (period - 1) + dx[i]) / period;
            adx[i] = prev;
        }
        return new Adx(adx, plusDi, minusDi);
    }

    /** Bollinger Bands: SMA middle band with k population standard deviations. */
    public static Bollinger bollinger(double[] close, int period, double k) {
        double[] mid = sma(close, period);
        int n = close.length;
        double[] up = Numerics.nanArray(n);
        double[] lo = Numerics.nanArray(n);
        for (int i = period - 1; i < n; i++) {
            double sd = Stats.populationStdDev(close, i - period + 1, i + 1);
            up[i] = mid[i] + k * sd;
            lo[i] = mid[i] - k * sd;
        }
        return new Bollinger(up, mid, lo);
    }

    /** Keltner Channel: EMA middle band with ATR-based envelope. */
    public static Keltner keltner(BarSeries s, int emaPeriod, int atrPeriod, double multiplier) {
        double[] mid = ema(s.closesCopy(), emaPeriod);
        double[] a = atr(s, atrPeriod);
        int n = s.size();
        double[] up = Numerics.nanArray(n);
        double[] lo = Numerics.nanArray(n);
        for (int i = 0; i < n; i++) {
            if (!Double.isNaN(mid[i]) && !Double.isNaN(a[i])) {
                up[i] = mid[i] + multiplier * a[i];
                lo[i] = mid[i] - multiplier * a[i];
            }
        }
        return new Keltner(up, mid, lo);
    }

    /** Donchian Channel: highest high / lowest low over the period. */
    public static Donchian donchian(BarSeries s, int period) {
        checkPeriod(period);
        int n = s.size();
        double[] up = highest(s.highsCopy(), period);
        double[] lo = lowest(s.lowsCopy(), period);
        double[] mid = Numerics.nanArray(n);
        for (int i = 0; i < n; i++) {
            if (!Double.isNaN(up[i])) {
                mid[i] = (up[i] + lo[i]) / 2;
            }
        }
        return new Donchian(up, mid, lo);
    }

    // ------------------------------------------------------------------
    // Volume
    // ------------------------------------------------------------------

    /** On-Balance Volume. */
    public static double[] obv(BarSeries s) {
        int n = s.size();
        double[] out = new double[n];
        for (int i = 1; i < n; i++) {
            double d = s.close(i) - s.close(i - 1);
            out[i] = out[i - 1] + (d > 0 ? s.volume(i) : d < 0 ? -s.volume(i) : 0);
        }
        return out;
    }

    /** Cumulative Volume-Weighted Average Price (anchored at the series start). */
    public static double[] vwap(BarSeries s) {
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

    /** Chaikin Money Flow. */
    public static double[] cmf(BarSeries s, int period) {
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

    // ------------------------------------------------------------------
    // Trend systems
    // ------------------------------------------------------------------

    /** SuperTrend with ATR bands. */
    public static SuperTrend superTrend(BarSeries s, int period, double multiplier) {
        int n = s.size();
        double[] a = atr(s, period);
        double[] value = Numerics.nanArray(n);
        int[] dir = new int[n];
        double fUp = Double.NaN, fLo = Double.NaN;
        int trend = 1;
        for (int i = period - 1; i < n; i++) {
            double mid = (s.high(i) + s.low(i)) / 2;
            double bUp = mid + multiplier * a[i];
            double bLo = mid - multiplier * a[i];
            if (Double.isNaN(fUp)) {
                fUp = bUp;
                fLo = bLo;
            } else {
                fUp = (bUp < fUp || s.close(i - 1) > fUp) ? bUp : fUp;
                fLo = (bLo > fLo || s.close(i - 1) < fLo) ? bLo : fLo;
            }
            if (trend == 1 && s.close(i) < fLo) {
                trend = -1;
            } else if (trend == -1 && s.close(i) > fUp) {
                trend = 1;
            }
            dir[i] = trend;
            value[i] = trend == 1 ? fLo : fUp;
        }
        return new SuperTrend(value, dir);
    }

    /**
     * Ichimoku Cloud with standard forward/backward displacement:
     * senkou spans are plotted {@code kijunPeriod} bars ahead, chikou
     * {@code kijunPeriod} bars behind. The chikou array is display-only and
     * contains future prices relative to its index; see {@link Ichimoku}.
     */
    public static Ichimoku ichimoku(BarSeries s, int tenkanPeriod, int kijunPeriod, int senkouBPeriod) {
        int n = s.size();
        double[] tenkan = midChannel(s, tenkanPeriod);
        double[] kijun = midChannel(s, kijunPeriod);
        double[] senkouA = Numerics.nanArray(n);
        double[] senkouB = Numerics.nanArray(n);
        double[] chikou = Numerics.nanArray(n);
        double[] spanBBase = midChannel(s, senkouBPeriod);
        for (int i = 0; i < n; i++) {
            int fwd = i + kijunPeriod;
            if (fwd < n) {
                if (!Double.isNaN(tenkan[i]) && !Double.isNaN(kijun[i])) {
                    senkouA[fwd] = (tenkan[i] + kijun[i]) / 2;
                }
                senkouB[fwd] = spanBBase[i];
            }
            int back = i - kijunPeriod;
            if (back >= 0) {
                chikou[back] = s.close(i);
            }
        }
        return new Ichimoku(tenkan, kijun, senkouA, senkouB, chikou);
    }

    private static double[] midChannel(BarSeries s, int period) {
        double[] hh = highest(s.highsCopy(), period);
        double[] ll = lowest(s.lowsCopy(), period);
        double[] out = Numerics.nanArray(s.size());
        for (int i = 0; i < out.length; i++) {
            if (!Double.isNaN(hh[i])) {
                out[i] = (hh[i] + ll[i]) / 2;
            }
        }
        return out;
    }

    /** Parabolic SAR (standard Wilder acceleration schedule). */
    public static double[] parabolicSar(BarSeries s, double afStart, double afStep, double afMax) {
        int n = s.size();
        double[] out = Numerics.nanArray(n);
        if (n < 2) {
            return out;
        }
        boolean up = s.close(1) >= s.close(0);
        double sar = up ? Math.min(s.low(0), s.low(1)) : Math.max(s.high(0), s.high(1));
        double ep = up ? Math.max(s.high(0), s.high(1)) : Math.min(s.low(0), s.low(1));
        double af = afStart;
        out[1] = sar;
        for (int i = 2; i < n; i++) {
            sar += af * (ep - sar);
            if (up) {
                sar = Math.min(sar, Math.min(s.low(i - 1), s.low(i - 2)));
                if (s.low(i) < sar) {           // reversal to downtrend
                    up = false;
                    sar = ep;
                    ep = s.low(i);
                    af = afStart;
                } else if (s.high(i) > ep) {
                    ep = s.high(i);
                    af = Math.min(af + afStep, afMax);
                }
            } else {
                sar = Math.max(sar, Math.max(s.high(i - 1), s.high(i - 2)));
                if (s.high(i) > sar) {          // reversal to uptrend
                    up = true;
                    sar = ep;
                    ep = s.high(i);
                    af = afStart;
                } else if (s.low(i) < ep) {
                    ep = s.low(i);
                    af = Math.min(af + afStep, afMax);
                }
            }
            out[i] = sar;
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Rolling helpers
    // ------------------------------------------------------------------

    public static double[] highest(double[] v, int period) {
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

    public static double[] lowest(double[] v, int period) {
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

    /** Rolling population standard deviation. */
    public static double[] rollingStd(double[] v, int period) {
        checkPeriod(period);
        double[] out = Numerics.nanArray(v.length);
        for (int i = period - 1; i < v.length; i++) {
            out[i] = Stats.populationStdDev(v, i - period + 1, i + 1);
        }
        return out;
    }

    private static double[] smoothIgnoringNan(double[] v, int period) {
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

    private static void checkPeriod(int period) {
        if (period < 1) {
            throw new IllegalArgumentException("period must be >= 1: " + period);
        }
    }
}
