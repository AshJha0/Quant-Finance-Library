package com.quantfinlib.alpha;

import com.quantfinlib.core.BarSeries;
import com.quantfinlib.screener.Fundamentals;

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
            // Single forward pass carrying both price EMAs and the signal
            // EMA together: O(4·slow) per score instead of rebuilding each
            // EMA per MACD point, and the start clamp at bar 0 means the
            // seeds are real prices, never fabricated zeros.
            int start = Math.max(0, i - 4 * slow + 1);
            double kFast = 2.0 / (fast + 1);
            double kSlow = 2.0 / (slow + 1);
            double kSignal = 2.0 / (signal + 1);
            double emaFast = s.close(start);
            double emaSlow = s.close(start);
            double signalLine = Double.NaN;
            double macdLine = 0;
            for (int j = start; j <= i; j++) {
                if (j > start) {
                    double close = s.close(j);
                    emaFast += kFast * (close - emaFast);
                    emaSlow += kSlow * (close - emaSlow);
                }
                macdLine = emaFast - emaSlow;
                // The signal EMA seeds from the first MACD value in the pass.
                signalLine = Double.isNaN(signalLine)
                        ? macdLine
                        : signalLine + kSignal * (macdLine - signalLine);
            }
            return (macdLine - signalLine) / s.close(i);
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
     * (RSI 30 → +0.4) score positively.
     *
     * <p><b>Definition note</b>: this is <em>Cutler's</em> RSI (arithmetic
     * average of gains/losses over the window), chosen because it is
     * stateless and exactly recomputable at any bar. It is NOT the same
     * number as {@code indicators.Indicators#rsi}, which uses Wilder
     * smoothing — after a trend the two can disagree near the 30/70
     * thresholds. The factor name says so to keep reports unambiguous.</p>
     */
    public static AlphaFactor rsi(int period) {
        requireWindow(period > 0, "need period > 0");
        return named("RSI_CUTLER_REV(" + period + ")", period + 1, (s, i) -> {
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

    /** Per-symbol window math, unboxed — the score kernel of every technical factor. */
    @FunctionalInterface
    private interface ScoreFn {
        double score(BarSeries series, int index);
    }

    /** Wraps per-symbol window math into the cross-sectional contract. */
    private static AlphaFactor named(String name, int minBars, ScoreFn perSymbol) {
        return new AlphaFactor() {
            @Override
            public double[] scores(AlphaContext ctx, int index) {
                double[] out = new double[ctx.symbolCount()];
                for (int i = 0; i < out.length; i++) {
                    if (index < minBars) {
                        // NaN below the warm-up: downstream steps skip it.
                        out[i] = Double.NaN;
                    } else if (!ctx.isActive(i, index)) {
                        // Point-in-time universe gate: dead/non-member names
                        // never enter the cross-section (see AlphaContext).
                        out[i] = Double.NaN;
                    } else {
                        out[i] = perSymbol.score(ctx.series(i), index);
                    }
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
                    out[i] = fu == null || !ctx.isActive(i, index)
                            ? Double.NaN : f.applyAsDouble(fu);
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

    private static void requireWindow(boolean ok, String message) {
        if (!ok) {
            throw new IllegalArgumentException(message);
        }
    }
}
