package com.quantfinlib.indicators;

import com.quantfinlib.core.BarSeries;

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
        return MovingAverages.sma(v, period);
    }

    /** Exponential moving average, seeded with the SMA of the first {@code period} values. */
    public static double[] ema(double[] v, int period) {
        return MovingAverages.ema(v, period);
    }

    /** Linearly weighted moving average. */
    public static double[] wma(double[] v, int period) {
        return MovingAverages.wma(v, period);
    }

    // ------------------------------------------------------------------
    // Momentum / oscillators
    // ------------------------------------------------------------------

    /** Relative Strength Index (Wilder smoothing). */
    public static double[] rsi(double[] v, int period) {
        return Oscillators.rsi(v, period);
    }

    /** Rate of change, percent: (v[i] / v[i-period] - 1) * 100. */
    public static double[] roc(double[] v, int period) {
        return Oscillators.roc(v, period);
    }

    /** Momentum: v[i] - v[i-period]. */
    public static double[] momentum(double[] v, int period) {
        return Oscillators.momentum(v, period);
    }

    /** MACD: EMA(fast) - EMA(slow), with an EMA signal line and histogram. */
    public static Macd macd(double[] close, int fastPeriod, int slowPeriod, int signalPeriod) {
        return Oscillators.macd(close, fastPeriod, slowPeriod, signalPeriod);
    }

    /** Stochastic RSI: stochastic oscillator applied to RSI, with %K and %D smoothing. */
    public static StochRsi stochasticRsi(double[] close, int rsiPeriod, int stochPeriod,
                                         int kSmooth, int dSmooth) {
        return Oscillators.stochasticRsi(close, rsiPeriod, stochPeriod, kSmooth, dSmooth);
    }

    /** Williams %R: -100 * (highestHigh - close) / (highestHigh - lowestLow). */
    public static double[] williamsR(BarSeries s, int period) {
        return Oscillators.williamsR(s, period);
    }

    /** Commodity Channel Index over typical price. */
    public static double[] cci(BarSeries s, int period) {
        return Oscillators.cci(s, period);
    }

    // ------------------------------------------------------------------
    // Volatility / range
    // ------------------------------------------------------------------

    /** True range series. */
    public static double[] trueRange(BarSeries s) {
        return RangeIndicators.trueRange(s);
    }

    /** Average True Range (Wilder smoothing). */
    public static double[] atr(BarSeries s, int period) {
        return RangeIndicators.atr(s, period);
    }

    /** Average Directional Index with +DI / -DI (Wilder). */
    public static Adx adx(BarSeries s, int period) {
        return RangeIndicators.adx(s, period);
    }

    /** Bollinger Bands: SMA middle band with k population standard deviations. */
    public static Bollinger bollinger(double[] close, int period, double k) {
        return RangeIndicators.bollinger(close, period, k);
    }

    /** Keltner Channel: EMA middle band with ATR-based envelope. */
    public static Keltner keltner(BarSeries s, int emaPeriod, int atrPeriod, double multiplier) {
        return RangeIndicators.keltner(s, emaPeriod, atrPeriod, multiplier);
    }

    /** Donchian Channel: highest high / lowest low over the period. */
    public static Donchian donchian(BarSeries s, int period) {
        return RangeIndicators.donchian(s, period);
    }

    // ------------------------------------------------------------------
    // Volume
    // ------------------------------------------------------------------

    /** On-Balance Volume. */
    public static double[] obv(BarSeries s) {
        return VolumeIndicators.obv(s);
    }

    /** Cumulative Volume-Weighted Average Price (anchored at the series start). */
    public static double[] vwap(BarSeries s) {
        return VolumeIndicators.vwap(s);
    }

    /** Chaikin Money Flow. */
    public static double[] cmf(BarSeries s, int period) {
        return VolumeIndicators.cmf(s, period);
    }

    // ------------------------------------------------------------------
    // Trend systems
    // ------------------------------------------------------------------

    /** SuperTrend with ATR bands. */
    public static SuperTrend superTrend(BarSeries s, int period, double multiplier) {
        return TrendIndicators.superTrend(s, period, multiplier);
    }

    /**
     * Ichimoku Cloud with standard forward/backward displacement:
     * senkou spans are plotted {@code kijunPeriod} bars ahead, chikou
     * {@code kijunPeriod} bars behind. The chikou array is display-only and
     * contains future prices relative to its index; see {@link Ichimoku}.
     */
    public static Ichimoku ichimoku(BarSeries s, int tenkanPeriod, int kijunPeriod, int senkouBPeriod) {
        return TrendIndicators.ichimoku(s, tenkanPeriod, kijunPeriod, senkouBPeriod);
    }

    /** Parabolic SAR (standard Wilder acceleration schedule). */
    public static double[] parabolicSar(BarSeries s, double afStart, double afStep, double afMax) {
        return TrendIndicators.parabolicSar(s, afStart, afStep, afMax);
    }

    // ------------------------------------------------------------------
    // Rolling helpers
    // ------------------------------------------------------------------

    public static double[] highest(double[] v, int period) {
        return RollingWindows.highest(v, period);
    }

    public static double[] lowest(double[] v, int period) {
        return RollingWindows.lowest(v, period);
    }

    /** Rolling population standard deviation. */
    public static double[] rollingStd(double[] v, int period) {
        return RollingWindows.rollingStd(v, period);
    }
}
