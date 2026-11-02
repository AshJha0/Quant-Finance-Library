package com.quantfinlib.indicators;

import com.quantfinlib.core.BarSeries;
import com.quantfinlib.stats.Numerics;
import com.quantfinlib.stats.Stats;
import com.quantfinlib.indicators.Indicators.Macd;
import com.quantfinlib.indicators.Indicators.StochRsi;

import java.util.Arrays;

import static com.quantfinlib.indicators.Indicators.ema;
import static com.quantfinlib.indicators.IndicatorSupport.checkPeriod;
import static com.quantfinlib.indicators.IndicatorSupport.smoothIgnoringNan;

/**
 * Momentum oscillators: RSI, ROC, momentum, MACD, stochastic RSI, Williams %R, CCI.
 * Package-private implementation behind the {@link Indicators} facade,
 * split out in 3.1 with no change in behaviour.
 */
final class Oscillators {

    private Oscillators() {
    }

    static double[] rsi(double[] v, int period) {
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

    static double[] roc(double[] v, int period) {
        checkPeriod(period);
        double[] out = Numerics.nanArray(v.length);
        for (int i = period; i < v.length; i++) {
            out[i] = (v[i] / v[i - period] - 1) * 100;
        }
        return out;
    }

    static double[] momentum(double[] v, int period) {
        checkPeriod(period);
        double[] out = Numerics.nanArray(v.length);
        for (int i = period; i < v.length; i++) {
            out[i] = v[i] - v[i - period];
        }
        return out;
    }

    static Macd macd(double[] close, int fastPeriod, int slowPeriod, int signalPeriod) {
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

    static StochRsi stochasticRsi(double[] close, int rsiPeriod, int stochPeriod,
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

    static double[] williamsR(BarSeries s, int period) {
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

    static double[] cci(BarSeries s, int period) {
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
}
