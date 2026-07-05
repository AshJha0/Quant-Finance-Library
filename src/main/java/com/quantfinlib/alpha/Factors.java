package com.quantfinlib.alpha;

import com.quantfinlib.core.BarSeries;
import com.quantfinlib.screener.Fundamentals;

import java.util.function.BiFunction;

/**
 * The standard alpha factor library — nine signal generators covering the
 * classic technical, factor-investing and defensive families. Each returns
 * an {@link AlphaFactor} producing raw cross-sectional scores where
 * <b>higher = more attractive long</b> (see the interface contract).
 *
 * <p>Sign conventions are chosen so every factor is usable long/short
 * as-is:</p>
 * <ul>
 *   <li><b>Trend</b> (MA crossover, MACD, momentum) score positively when
 *       the trend is up;</li>
 *   <li><b>Mean reversion</b> (RSI, Bollinger, mean reversion) score
 *       positively when the price is <em>depressed</em> — the contrarian
 *       orientation, since these signals bet on the snap-back;</li>
 *   <li><b>Defensive/fundamental</b> (value, quality, low volatility)
 *       score positively for cheap, profitable, calm names — the
 *       Fama-French/AQR orientation of each anomaly.</li>
 * </ul>
 *
 * <p>All computations read only bars {@code <= index} (the no-look-ahead
 * contract) and cost O(window) per symbol per call — stateless by design so
 * factors are trivially safe to evaluate at arbitrary dates, at the price
 * of recomputing windows the streaming indicators would carry. EMAs are
 * truncated at 4× their period, where the dropped tail weight is
 * {@code (1−2/(p+1))^{4p} < 0.04%} — far below signal noise.</p>
 */
public final class Factors {

    private Factors() {
    }

    // ------------------------------------------------------------------
    // Trend family
    // ------------------------------------------------------------------

    /**
     * Moving-average crossover: {@code (SMA_fast − SMA_slow) / SMA_slow}.
     * Positive when the fast average rides above the slow — the % spread
     * makes scores comparable across price levels.
     */
    public static AlphaFactor movingAverageCrossover(int fast, int slow) {
        requireWindow(fast > 0 && slow > fast, "need slow > fast > 0");
        return named("MA_CROSS(" + fast + "," + slow + ")", slow, (s, i) -> {
            double f = sma(s, i, fast);
            double sl = sma(s, i, slow);
            return (f - sl) / sl;
        });
    }

    /**
     * MACD histogram normalized by price: {@code (macdLine − signalLine) / close}.
     * Positive while bullish momentum is accelerating; the price
     * normalization keeps a $10 and a $1000 stock on one scale.
     */
    public static AlphaFactor macd(int fast, int slow, int signal) {
        requireWindow(fast > 0 && slow > fast && signal > 0, "need slow > fast > 0, signal > 0");
        // Warm-up: one slow window plus the signal window. The truncated EMA
        // self-seeds from whatever history exists, so longer waits buy only
        // the sub-0.04% truncation tail — not worth NaN-ing early scores.
        int warmup = slow + signal;
        return named("MACD(" + fast + "," + slow + "," + signal + ")", warmup, (s, i) -> {
            // Signal line is an EMA over the MACD line; rebuild the last
            // 4×signal MACD points (each O(window)) — stateless but exact
            // to truncation error.
            int points = 4 * signal;
            double signalLine = Double.NaN;
            double k = 2.0 / (signal + 1);
            double macdAtI = Double.NaN;
            for (int j = i - points + 1; j <= i; j++) {
                double m = ema(s, j, fast) - ema(s, j, slow);
                signalLine = Double.isNaN(signalLine) ? m : signalLine + k * (m - signalLine);
                if (j == i) {
                    macdAtI = m;
                }
            }
            return (macdAtI - signalLine) / s.close(i);
        });
    }

    /**
     * Cross-sectional momentum: {@code close[i−skip] / close[i−lookback] − 1}.
     * The academic 12-1 form when called as {@code momentum(252, 21)} —
     * skipping the last month sidesteps short-term reversal
     * (Jegadeesh-Titman 1993).
     */
    public static AlphaFactor momentum(int lookback, int skip) {
        requireWindow(lookback > 0 && skip >= 0 && skip < lookback, "need lookback > skip >= 0");
        return named("MOMENTUM(" + lookback + "-" + skip + ")", lookback,
                (s, i) -> s.close(i - skip) / s.close(i - lookback) - 1);
    }

    // ------------------------------------------------------------------
    // Mean-reversion family (contrarian sign: depressed prices score high)
    // ------------------------------------------------------------------

    /**
     * Contrarian RSI: {@code (50 − RSI) / 50}, in [−1, +1]. Oversold names
     * (RSI 30 → +0.4) score positively. Cutler's simple-average RSI is used
     * (arithmetic gains/losses over the window) so the factor is stateless.
     */
    public static AlphaFactor rsi(int period) {
        requireWindow(period > 0, "need period > 0");
        return named("RSI_REV(" + period + ")", period + 1, (s, i) -> {
            double gain = 0;
            double loss = 0;
            for (int j = i - period + 1; j <= i; j++) {
                double change = s.close(j) - s.close(j - 1);
                if (change > 0) {
                    gain += change;
                } else {
                    loss -= change;
                }
            }
            if (gain + loss == 0) {
                return 0.0; // flat window: neither overbought nor oversold
            }
            double rsi = 100 * gain / (gain + loss);
            return (50 - rsi) / 50;
        });
    }

    /**
     * Bollinger mean reversion: {@code −(close − SMA) / (k·σ)} — the
     * negative band position, +1 at the lower band, −1 at the upper.
     */
    public static AlphaFactor bollinger(int period, double stdDevs) {
        requireWindow(period > 1 && stdDevs > 0, "need period > 1 and stdDevs > 0");
        return named("BOLL_REV(" + period + "," + stdDevs + ")", period, (s, i) -> {
            double mean = sma(s, i, period);
            double var = 0;
            for (int j = i - period + 1; j <= i; j++) {
                double d = s.close(j) - mean;
                var += d * d;
            }
            double sd = Math.sqrt(var / period);
            if (sd == 0) {
                return 0.0; // flat window: no band to revert within
            }
            return -(s.close(i) - mean) / (stdDevs * sd);
        });
    }

    /**
     * Plain mean reversion: {@code −(close / SMA − 1)} — how far the price
     * sits below its own average, as a fraction.
     */
    public static AlphaFactor meanReversion(int lookback) {
        requireWindow(lookback > 0, "need lookback > 0");
        return named("MEAN_REV(" + lookback + ")", lookback,
                (s, i) -> -(s.close(i) / sma(s, i, lookback) - 1));
    }

    // ------------------------------------------------------------------
    // Fundamental / defensive family
    // ------------------------------------------------------------------

    /**
     * Value composite: the average of earnings yield ({@code 1/PE}) and
     * book yield ({@code 1/PB}) — yields, not ratios, so "cheap" is high
     * and negative-earnings names contribute a negative yield rather than
     * a meaningless negative PE rank. NaN without fundamentals.
     */
    public static AlphaFactor value() {
        return fundamental("VALUE", f -> {
            double sum = 0;
            int n = 0;
            if (!Double.isNaN(f.peRatio()) && f.peRatio() != 0) {
                sum += 1 / f.peRatio();
                n++;
            }
            if (!Double.isNaN(f.pbRatio()) && f.pbRatio() != 0) {
                sum += 1 / f.pbRatio();
                n++;
            }
            return n == 0 ? Double.NaN : sum / n;
        });
    }

    /**
     * Quality composite: profitability minus leverage —
     * {@code ROE − 0.1 × debt/equity}. The 0.1 haircut puts one turn of
     * leverage on the same scale as 10 points of ROE, the usual
     * quality-minus-junk shape (profitable AND conservatively financed).
     */
    public static AlphaFactor quality() {
        return fundamental("QUALITY", f -> {
            double roe = f.roe();
            double leverage = Double.isNaN(f.debtToEquity()) ? 0 : f.debtToEquity();
            return Double.isNaN(roe) ? Double.NaN : roe - 0.1 * leverage;
        });
    }

    /**
     * Low-volatility anomaly: {@code −σ(returns)} over the lookback —
     * calm names score high. Ranking only needs the negative sign, not
     * annualization.
     */
    public static AlphaFactor lowVolatility(int lookback) {
        requireWindow(lookback > 1, "need lookback > 1");
        return named("LOW_VOL(" + lookback + ")", lookback + 1, (s, i) -> {
            double mean = 0;
            for (int j = i - lookback + 1; j <= i; j++) {
                mean += s.close(j) / s.close(j - 1) - 1;
            }
            mean /= lookback;
            double var = 0;
            for (int j = i - lookback + 1; j <= i; j++) {
                double d = (s.close(j) / s.close(j - 1) - 1) - mean;
                var += d * d;
            }
            return -Math.sqrt(var / lookback);
        });
    }

    // ------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------

    /** Wraps per-symbol window math into the cross-sectional contract. */
    private static AlphaFactor named(String name, int minBars,
                                     BiFunction<BarSeries, Integer, Double> perSymbol) {
        return new AlphaFactor() {
            @Override
            public double[] scores(AlphaContext ctx, int index) {
                double[] out = new double[ctx.symbolCount()];
                for (int i = 0; i < out.length; i++) {
                    // NaN below the warm-up: downstream steps skip it.
                    out[i] = index < minBars ? Double.NaN
                            : perSymbol.apply(ctx.series(i), index);
                }
                return out;
            }

            @Override
            public String name() {
                return name;
            }
        };
    }

    /** Wraps a fundamentals read; NaN for symbols without a snapshot. */
    private static AlphaFactor fundamental(String name,
                                           java.util.function.ToDoubleFunction<Fundamentals> f) {
        return new AlphaFactor() {
            @Override
            public double[] scores(AlphaContext ctx, int index) {
                double[] out = new double[ctx.symbolCount()];
                for (int i = 0; i < out.length; i++) {
                    Fundamentals fu = ctx.fundamentals(i);
                    out[i] = fu == null ? Double.NaN : f.applyAsDouble(fu);
                }
                return out;
            }

            @Override
            public String name() {
                return name;
            }
        };
    }

    private static double sma(BarSeries s, int index, int period) {
        double sum = 0;
        for (int j = index - period + 1; j <= index; j++) {
            sum += s.close(j);
        }
        return sum / period;
    }

    /** Truncated EMA: seeded 4 periods back (dropped weight < 0.04%). */
    private static double ema(BarSeries s, int index, int period) {
        int start = Math.max(0, index - 4 * period + 1);
        double k = 2.0 / (period + 1);
        double ema = s.close(start);
        for (int j = start + 1; j <= index; j++) {
            ema += k * (s.close(j) - ema);
        }
        return ema;
    }

    private static void requireWindow(boolean ok, String message) {
        if (!ok) {
            throw new IllegalArgumentException(message);
        }
    }
}
