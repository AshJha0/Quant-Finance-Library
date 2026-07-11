package com.quantfinlib.screener;

import com.quantfinlib.core.BarSeries;
import com.quantfinlib.indicators.Indicators;
import com.quantfinlib.util.MathUtils;

/**
 * Technical screening filters evaluated on the most recent bar. All filters
 * are NaN-safe and return {@code false} when the series is too short.
 *
 * <p>Design contract worth stating: a screen answers "does this stock look
 * like X TODAY", so every filter reads only the LAST valid indicator value
 * — never a history of signals (that is a backtest's job, over in
 * {@code backtest}). "Too short = false" rather than "too short = throw"
 * is deliberate: a screen runs across an entire universe, and one
 * recently-listed ticker with 30 bars must silently drop out of an
 * SMA(200) screen, not kill the run for the other 2,999 names. The cost
 * of that choice is that {@code rsiBelow(14, 70)} and "not enough data"
 * are indistinguishable in the output — compose an explicit
 * length/liquidity pre-filter first when the distinction matters.
 * Filters compose via {@link ScreenFilter#and}/{@code or}/{@code negate};
 * feed survivors to {@link RankingEngine} to order them.</p>
 */
public final class TechnicalFilters {

    private TechnicalFilters() {
    }

    public static ScreenFilter rsiBelow(int period, double value) {
        return s -> lastValid(Indicators.rsi(s.series().closes(), period)) < value;
    }

    public static ScreenFilter rsiAbove(int period, double value) {
        return s -> lastValid(Indicators.rsi(s.series().closes(), period)) > value;
    }

    public static ScreenFilter priceAboveSma(int period) {
        return s -> s.lastClose() > lastValid(Indicators.sma(s.series().closes(), period));
    }

    public static ScreenFilter priceBelowSma(int period) {
        return s -> s.lastClose() < lastValid(Indicators.sma(s.series().closes(), period));
    }

    public static ScreenFilter priceAboveEma(int period) {
        return s -> s.lastClose() > lastValid(Indicators.ema(s.series().closes(), period));
    }

    /** MACD line above its signal line on the last bar. */
    public static ScreenFilter macdBullish() {
        return s -> {
            Indicators.Macd m = Indicators.macd(s.series().closes(), 12, 26, 9);
            int i = s.series().size() - 1;
            return valid(m.line()[i], m.signal()[i]) && m.line()[i] > m.signal()[i];
        };
    }

    public static ScreenFilter adxAbove(int period, double value) {
        return s -> lastValid(Indicators.adx(s.series(), period).adx()) > value;
    }

    /** ATR as a fraction of price below the threshold (low-volatility screen). */
    public static ScreenFilter atrPercentBelow(int period, double maxFraction) {
        return s -> {
            double atr = lastValid(Indicators.atr(s.series(), period));
            return !Double.isNaN(atr) && atr / s.lastClose() < maxFraction;
        };
    }

    public static ScreenFilter priceAboveVwap() {
        return s -> s.lastClose() > lastValid(Indicators.vwap(s.series()));
    }

    public static ScreenFilter superTrendBullish(int period, double multiplier) {
        return s -> {
            Indicators.SuperTrend st = Indicators.superTrend(s.series(), period, multiplier);
            return st.direction()[s.series().size() - 1] == 1;
        };
    }

    /** Close above the upper Bollinger band (volatility breakout). */
    public static ScreenFilter bollingerBreakout(int period, double k) {
        return s -> {
            double upper = lastValid(Indicators.bollinger(s.series().closes(), period, k).upper());
            return !Double.isNaN(upper) && s.lastClose() > upper;
        };
    }

    /** Close above both Ichimoku cloud spans on the last bar. */
    public static ScreenFilter aboveIchimokuCloud() {
        return s -> {
            Indicators.Ichimoku ich = Indicators.ichimoku(s.series(), 9, 26, 52);
            int i = s.series().size() - 1;
            double a = ich.senkouA()[i], b = ich.senkouB()[i];
            return valid(a, b) && s.lastClose() > Math.max(a, b);
        };
    }

    /** Close breaks above the highest high of the previous {@code lookback} bars. */
    public static ScreenFilter breakout(int lookback) {
        return s -> {
            BarSeries series = s.series();
            int n = series.size();
            if (n < lookback + 2) {
                return false;
            }
            double hh = Double.NEGATIVE_INFINITY;
            for (int i = n - 1 - lookback; i < n - 1; i++) {
                hh = Math.max(hh, series.high(i));
            }
            return series.close(n - 1) > hh;
        };
    }

    /** Last bar volume exceeds {@code multiplier} times the prior average volume. */
    public static ScreenFilter volumeSpike(int lookback, double multiplier) {
        return s -> {
            BarSeries series = s.series();
            int n = series.size();
            if (n < lookback + 2) {
                return false;
            }
            double avg = MathUtils.mean(series.volumes(), n - 1 - lookback, n - 1);
            return avg > 0 && series.volume(n - 1) > multiplier * avg;
        };
    }

    /** Gap up at the last open of at least {@code minFraction} versus the prior close. */
    public static ScreenFilter gapUp(double minFraction) {
        return s -> {
            BarSeries series = s.series();
            int n = series.size();
            return n >= 2 && series.open(n - 1) >= series.close(n - 2) * (1 + minFraction);
        };
    }

    /** Close within {@code withinFraction} of the 52-week (252-bar) high. */
    public static ScreenFilter near52WeekHigh(double withinFraction) {
        return s -> {
            BarSeries series = s.series();
            int n = series.size();
            int lookback = Math.min(252, n);
            double hh = Double.NEGATIVE_INFINITY;
            for (int i = n - lookback; i < n; i++) {
                hh = Math.max(hh, series.high(i));
            }
            return series.close(n - 1) >= hh * (1 - withinFraction);
        };
    }

    /** Close within {@code withinFraction} of the 52-week (252-bar) low. */
    public static ScreenFilter near52WeekLow(double withinFraction) {
        return s -> {
            BarSeries series = s.series();
            int n = series.size();
            int lookback = Math.min(252, n);
            double ll = Double.POSITIVE_INFINITY;
            for (int i = n - lookback; i < n; i++) {
                ll = Math.min(ll, series.low(i));
            }
            return series.close(n - 1) <= ll * (1 + withinFraction);
        };
    }

    // ------------------------------------------------------------------

    private static double lastValid(double[] v) {
        double last = v[v.length - 1];
        return Double.isNaN(last) ? Double.NaN : last;
    }

    private static boolean valid(double... values) {
        for (double v : values) {
            if (Double.isNaN(v)) {
                return false;
            }
        }
        return true;
    }
}
