package com.quantfinlib.indicators;

import com.quantfinlib.core.BarSeries;
import com.quantfinlib.stats.Numerics;
import com.quantfinlib.stats.Stats;
import com.quantfinlib.indicators.Indicators.Adx;
import com.quantfinlib.indicators.Indicators.Bollinger;
import com.quantfinlib.indicators.Indicators.Donchian;
import com.quantfinlib.indicators.Indicators.Keltner;

import static com.quantfinlib.indicators.Indicators.ema;
import static com.quantfinlib.indicators.Indicators.highest;
import static com.quantfinlib.indicators.Indicators.lowest;
import static com.quantfinlib.indicators.Indicators.sma;
import static com.quantfinlib.indicators.IndicatorSupport.checkPeriod;

/**
 * Volatility and range indicators: true range, ATR, ADX, Bollinger, Keltner, Donchian.
 * Package-private implementation behind the {@link Indicators} facade,
 * split out in 3.1 with no change in behaviour.
 */
final class RangeIndicators {

    private RangeIndicators() {
    }

    static double[] trueRange(BarSeries s) {
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

    static double[] atr(BarSeries s, int period) {
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

    static Adx adx(BarSeries s, int period) {
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

    static Bollinger bollinger(double[] close, int period, double k) {
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

    static Keltner keltner(BarSeries s, int emaPeriod, int atrPeriod, double multiplier) {
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

    static Donchian donchian(BarSeries s, int period) {
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
}
