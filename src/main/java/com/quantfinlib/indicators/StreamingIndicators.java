package com.quantfinlib.indicators;

/**
 * Incremental O(1)-per-tick indicators for live/HFT strategies: update state
 * with each new value instead of recomputing arrays. Zero allocation after
 * construction, and numerically identical to the batch {@link Indicators}
 * implementations (same seeding and smoothing), so a strategy backtested on
 * batch arrays behaves the same when run live on the streaming versions.
 *
 * <p>Instances are single-threaded by design — one per strategy/consumer
 * thread — matching the single-consumer dispatch model of the HFT bus.</p>
 */
public final class StreamingIndicators {

    private StreamingIndicators() {
    }

    /** Simple moving average over a fixed window; NaN until the window fills. */
    public static final class Sma {
        private final double[] window;
        private final int period;
        private double sum;
        private long count;
        private int idx;
        private double value = Double.NaN;

        public Sma(int period) {
            this.period = period;
            this.window = new double[period];
        }

        public double update(double v) {
            if (count >= period) {
                sum -= window[idx];
            }
            window[idx] = v;
            sum += v;
            idx = idx + 1 == period ? 0 : idx + 1;
            count++;
            value = count >= period ? sum / period : Double.NaN;
            return value;
        }

        public double value() {
            return value;
        }
    }

    /** Exponential moving average seeded with the SMA of the first {@code period} values. */
    public static final class Ema {
        private final int period;
        private final double k;
        private double seedSum;
        private long count;
        private double value = Double.NaN;

        public Ema(int period) {
            this.period = period;
            this.k = 2.0 / (period + 1);
        }

        public double update(double v) {
            count++;
            if (count < period) {
                seedSum += v;
                return Double.NaN;
            }
            if (count == period) {
                value = (seedSum + v) / period;
            } else {
                value += (v - value) * k;
            }
            return value;
        }

        public double value() {
            return value;
        }
    }

    /** Wilder RSI; NaN until {@code period} price changes have been observed. */
    public static final class Rsi {
        private final int period;
        private double prev = Double.NaN;
        private long changes;
        private double gainSum;
        private double lossSum;
        private double avgGain;
        private double avgLoss;
        private double value = Double.NaN;

        public Rsi(int period) {
            this.period = period;
        }

        public double update(double v) {
            if (Double.isNaN(prev)) {
                prev = v;
                return Double.NaN;
            }
            double d = v - prev;
            prev = v;
            changes++;
            if (changes < period) {
                gainSum += Math.max(d, 0);
                lossSum += Math.max(-d, 0);
                return Double.NaN;
            }
            if (changes == period) {
                avgGain = (gainSum + Math.max(d, 0)) / period;
                avgLoss = (lossSum + Math.max(-d, 0)) / period;
            } else {
                avgGain = (avgGain * (period - 1) + Math.max(d, 0)) / period;
                avgLoss = (avgLoss * (period - 1) + Math.max(-d, 0)) / period;
            }
            value = toRsi(avgGain, avgLoss);
            return value;
        }

        private static double toRsi(double avgGain, double avgLoss) {
            if (avgLoss == 0) {
                return avgGain == 0 ? 50 : 100;
            }
            return 100 - 100 / (1 + avgGain / avgLoss);
        }

        public double value() {
            return value;
        }
    }

    /** MACD line, signal and histogram, matching the batch seeding exactly. */
    public static final class Macd {
        private final Ema fast;
        private final Ema slow;
        private final Ema signalEma;
        private double line = Double.NaN;
        private double signal = Double.NaN;
        private double histogram = Double.NaN;

        public Macd(int fastPeriod, int slowPeriod, int signalPeriod) {
            this.fast = new Ema(fastPeriod);
            this.slow = new Ema(slowPeriod);
            this.signalEma = new Ema(signalPeriod);
        }

        /** Returns the MACD line (NaN during warm-up). */
        public double update(double v) {
            double f = fast.update(v);
            double s = slow.update(v);
            if (Double.isNaN(s)) {
                return Double.NaN;
            }
            line = f - s;
            signal = signalEma.update(line);
            histogram = Double.isNaN(signal) ? Double.NaN : line - signal;
            return line;
        }

        public double line()      { return line; }
        public double signal()    { return signal; }
        public double histogram() { return histogram; }
    }

    /** Cumulative volume-weighted average price. */
    public static final class Vwap {
        private double cumPv;
        private double cumVol;
        private double value = Double.NaN;

        public double update(double price, double volume) {
            cumPv += price * volume;
            cumVol += volume;
            value = cumVol == 0 ? price : cumPv / cumVol;
            return value;
        }

        public double value() {
            return value;
        }
    }
}
