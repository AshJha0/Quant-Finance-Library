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
 * contract) and cost O(window) per symbol per call. They are stateless,
 * so they can be evaluated at arbitrary dates, at the price of recomputing
 * windows the streaming indicators would carry. EMAs are truncated at 4×
 * their period and seeded with the first price of that window; the dropped
 * tail weight is {@code (1−2/(p+1))^{4p} < 0.04%}.</p>
 *
 * <p><b>EMA definitions differ from {@code indicators.Indicators}.</b>
 * {@code Indicators.ema} seeds with the SMA of the first {@code period}
 * bars of the whole series and runs from bar 0; the factors here seed with
 * the first price of a {@code 4×slow} window ending at the scored bar. The
 * two converge as the seed's weight decays, but {@link #macd} and
 * {@code Indicators.macd} are not the same number on short histories, in
 * the same way {@link #rsi} (Cutler) is not Wilder's RSI.</p>
 *
 * <p><b>Cross-sectional preparation.</b> Raw technical scores are
 * dominated by market and sector moves in trending regimes, and their rank
 * IC then measures beta timing. {@link #winsorized}, {@link #standardized}
 * and {@link #sectorNeutralized} are per-date decorators; {@link #prepared}
 * applies them in the order that is correct for fat-tailed raw scores:
 * winsorise the raw score, then z-score, then demean within sector.</p>
 *
 * <p><b>Fundamentals</b> are read through
 * {@link AlphaContext#fundamentalsAsOf}: point-in-time with a
 * {@link FundamentalsHistory}; with only a static snapshot they can be
 * scored at the last bar and throw for every earlier bar.</p>
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
     * Value composite: the average of earnings yield and book yield
     * ({@code 1/PB}). Yields rather than ratios, so "cheap" is high and
     * negative-earnings names contribute a negative yield rather than a
     * meaningless negative P/E rank. The earnings yield is
     * {@code eps / close(t)} when {@code eps} is known, so the price side is
     * current; otherwise {@code 1/PE} as recorded. NaN without fundamentals.
     */
    public static AlphaFactor value() {
        return fundamental("VALUE", (f, price) -> {
            double sum = 0;
            int n = 0;
            if (Double.isFinite(f.eps()) && price > 0) {
                sum += f.eps() / price;
                n++;
            } else if (!Double.isNaN(f.peRatio()) && f.peRatio() != 0) {
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
        return fundamental("QUALITY", (f, price) -> {
            double roe = f.roe();
            double leverage = Double.isNaN(f.debtToEquity()) ? 0 : f.debtToEquity();
            return Double.isNaN(roe) ? Double.NaN : roe - 0.1 * leverage;
        });
    }

    /**
     * Low-volatility anomaly: {@code −σ(returns)} over the lookback, calm
     * names scoring high. Ranking only needs the negative sign, not
     * annualisation. Synthetic bars are excluded; NaN with fewer than two
     * real bars in the window.
     */
    public static AlphaFactor lowVolatility(int lookback) {
        requireWindow(lookback > 1, "need lookback > 1");
        return named("LOW_VOL(" + lookback + ")", lookback + 1, (s, i) -> {
            // Synthetic (forward-filled) bars are skipped: their zero
            // returns would make a halted or illiquid name look calm.
            double sum = 0;
            double sumSq = 0;
            int n = 0;
            for (int j = i - lookback + 1; j <= i; j++) {
                if (s.isSynthetic(j)) {
                    continue;
                }
                double r = s.close(j) / s.close(j - 1) - 1;
                sum += r;
                sumSq += r * r;
                n++;
            }
            if (n < 2) {
                return Double.NaN;
            }
            double mean = sum / n;
            return -Math.sqrt(Math.max(0, sumSq / n - mean * mean));
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

    /** Fundamental score kernel: fundamentals as of the bar plus that bar's close. */
    @FunctionalInterface
    private interface FundamentalFn {
        double score(Fundamentals f, double price);
    }

    /**
     * Wraps a point-in-time fundamentals read ({@link AlphaContext#fundamentalsAsOf});
     * NaN for symbols with nothing published yet.
     */
    private static AlphaFactor fundamental(String name, FundamentalFn f) {
        return new AlphaFactor() {
            @Override
            public double[] scores(AlphaContext ctx, int index) {
                double[] out = new double[ctx.symbolCount()];
                for (int i = 0; i < out.length; i++) {
                    if (!ctx.isActive(i, index)) {
                        out[i] = Double.NaN;
                        continue;
                    }
                    Fundamentals fu = ctx.fundamentalsAsOf(i, index);
                    out[i] = fu == null ? Double.NaN : f.score(fu, ctx.series(i).close(index));
                }
                return out;
            }

            @Override
            public String name() {
                return name;
            }
        };
    }

    // ------------------------------------------------------------------
    // Cross-sectional decorators (applied per date)
    // ------------------------------------------------------------------

    /**
     * Clips each date's scores to the cross-sectional {@code p} and
     * {@code 1 − p} quantiles (linear interpolation between order
     * statistics), NaN entries untouched.
     *
     * @param p tail fraction in [0, 0.5), for example 0.01
     */
    public static AlphaFactor winsorized(AlphaFactor factor, double p) {
        if (!(p >= 0 && p < 0.5)) {
            throw new IllegalArgumentException("p must be in [0, 0.5)");
        }
        return decorate("WINSOR(" + factor.name() + "," + p + ")", factor, (ctx, s) -> {
            double[] v = finite(s);
            if (v.length < 2) {
                return s;
            }
            java.util.Arrays.sort(v);
            double lo = quantile(v, p);
            double hi = quantile(v, 1 - p);
            double[] out = s.clone();
            for (int i = 0; i < out.length; i++) {
                if (!Double.isNaN(out[i])) {
                    out[i] = Math.max(lo, Math.min(hi, out[i]));
                }
            }
            return out;
        });
    }

    /**
     * Cross-sectional z-score per date: {@code (s − mean)/sd} over the
     * scored names (population sd); all-zero when the scores have no
     * dispersion.
     */
    public static AlphaFactor standardized(AlphaFactor factor) {
        return decorate("Z(" + factor.name() + ")", factor, (ctx, s) -> zScore(s));
    }

    /**
     * Demeans scores within each sector per date, so the factor ranks
     * names against their sector peers. Symbols missing from the map form
     * their own singleton sector and score 0.
     */
    public static AlphaFactor sectorNeutralized(AlphaFactor factor,
                                               java.util.Map<String, String> sectorBySymbol) {
        return decorate("SECNEUT(" + factor.name() + ")", factor, (ctx, s) -> {
            java.util.Map<String, double[]> sums = new java.util.HashMap<>();
            String[] sector = new String[s.length];
            for (int i = 0; i < s.length; i++) {
                sector[i] = sectorBySymbol.getOrDefault(ctx.symbols().get(i),
                        "\0UNKNOWN:" + ctx.symbols().get(i));
                if (!Double.isNaN(s[i])) {
                    double[] acc = sums.computeIfAbsent(sector[i], k -> new double[2]);
                    acc[0] += s[i];
                    acc[1]++;
                }
            }
            double[] out = s.clone();
            for (int i = 0; i < out.length; i++) {
                if (!Double.isNaN(out[i])) {
                    double[] acc = sums.get(sector[i]);
                    out[i] -= acc[0] / acc[1];
                }
            }
            return out;
        });
    }

    /**
     * The standard preparation chain: {@link #winsorized} at {@code p}, then
     * {@link #standardized}, then {@link #sectorNeutralized} (skipped when
     * {@code sectorBySymbol} is null).
     */
    public static AlphaFactor prepared(AlphaFactor factor, double p,
                                       java.util.Map<String, String> sectorBySymbol) {
        AlphaFactor f = standardized(winsorized(factor, p));
        return sectorBySymbol == null ? f : sectorNeutralized(f, sectorBySymbol);
    }

    /** z-score over the non-NaN entries; NaN stays NaN. */
    static double[] zScore(double[] s) {
        double sum = 0;
        int n = 0;
        for (double v : s) {
            if (!Double.isNaN(v)) {
                sum += v;
                n++;
            }
        }
        double[] out = s.clone();
        if (n == 0) {
            return out;
        }
        double mean = sum / n;
        double var = 0;
        for (double v : s) {
            if (!Double.isNaN(v)) {
                var += (v - mean) * (v - mean);
            }
        }
        double sd = Math.sqrt(var / n);
        for (int i = 0; i < out.length; i++) {
            if (!Double.isNaN(out[i])) {
                out[i] = sd == 0 ? 0 : (out[i] - mean) / sd;
            }
        }
        return out;
    }

    private static double[] finite(double[] s) {
        int n = 0;
        for (double v : s) {
            if (!Double.isNaN(v)) {
                n++;
            }
        }
        double[] out = new double[n];
        int k = 0;
        for (double v : s) {
            if (!Double.isNaN(v)) {
                out[k++] = v;
            }
        }
        return out;
    }

    /** Quantile of sorted data, linear between order statistics (Hyndman-Fan type 7). */
    private static double quantile(double[] sorted, double q) {
        double h = (sorted.length - 1) * q;
        int lo = (int) Math.floor(h);
        int hi = Math.min(sorted.length - 1, lo + 1);
        return sorted[lo] + (h - lo) * (sorted[hi] - sorted[lo]);
    }

    @FunctionalInterface
    private interface Transform {
        double[] apply(AlphaContext ctx, double[] scores);
    }

    private static AlphaFactor decorate(String name, AlphaFactor inner, Transform transform) {
        return new AlphaFactor() {
            @Override
            public double[] scores(AlphaContext ctx, int index) {
                return transform.apply(ctx, inner.scores(ctx, index));
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
