package com.quantfinlib.dsl;

/**
 * Factory of common {@link Rule}s over indicator arrays. All rules are
 * NaN-safe: a rule is never satisfied while its inputs are in warm-up.
 *
 * <p>The design decision worth knowing: a {@code Rule} is a predicate over
 * a BAR INDEX into precomputed indicator arrays, not over live values.
 * That keeps strategy definitions declarative ("RSI crossed under 30 AND
 * price above the 200-day") and — more importantly — makes look-ahead
 * bias structurally harder: the arrays are computed once by causal
 * indicator code, and a rule can only combine values at {@code i} and
 * {@code i-1}, never peek at {@code i+1}. Cross rules require the
 * previous bar to be on the other side (with {@code <=}/{@code >=}), so
 * a series that OPENS above the level does not count as a cross — the
 * classic off-by-one that fires a "breakout" signal on bar 0 of every
 * backtest. NaN warm-up bars satisfy nothing, and combining rules with
 * {@link Rule#and}/{@link Rule#or}/{@link Rule#not} preserves that.
 * Assembled into strategies by {@link StrategyBuilder}.</p>
 */
public final class Rules {

    private Rules() {
    }

    /** a crossed above b on this bar. */
    public static Rule crossAbove(double[] a, double[] b) {
        return i -> i > 0 && valid(a[i], b[i], a[i - 1], b[i - 1])
                && a[i - 1] <= b[i - 1] && a[i] > b[i];
    }

    /** a crossed below b on this bar. */
    public static Rule crossBelow(double[] a, double[] b) {
        return i -> i > 0 && valid(a[i], b[i], a[i - 1], b[i - 1])
                && a[i - 1] >= b[i - 1] && a[i] < b[i];
    }

    /** a crossed above a constant level on this bar. */
    public static Rule crossAboveValue(double[] a, double level) {
        return i -> i > 0 && valid(a[i], a[i - 1])
                && a[i - 1] <= level && a[i] > level;
    }

    /** a crossed below a constant level on this bar. */
    public static Rule crossBelowValue(double[] a, double level) {
        return i -> i > 0 && valid(a[i], a[i - 1])
                && a[i - 1] >= level && a[i] < level;
    }

    public static Rule above(double[] a, double[] b) {
        return i -> valid(a[i], b[i]) && a[i] > b[i];
    }

    public static Rule below(double[] a, double[] b) {
        return i -> valid(a[i], b[i]) && a[i] < b[i];
    }

    public static Rule aboveValue(double[] a, double level) {
        return i -> valid(a[i]) && a[i] > level;
    }

    public static Rule belowValue(double[] a, double level) {
        return i -> valid(a[i]) && a[i] < level;
    }

    /** a has risen on each of the last {@code bars} bars. */
    public static Rule rising(double[] a, int bars) {
        return i -> {
            if (i < bars) {
                return false;
            }
            for (int j = i - bars + 1; j <= i; j++) {
                if (!valid(a[j], a[j - 1]) || a[j] <= a[j - 1]) {
                    return false;
                }
            }
            return true;
        };
    }

    /** a has fallen on each of the last {@code bars} bars. */
    public static Rule falling(double[] a, int bars) {
        return i -> {
            if (i < bars) {
                return false;
            }
            for (int j = i - bars + 1; j <= i; j++) {
                if (!valid(a[j], a[j - 1]) || a[j] >= a[j - 1]) {
                    return false;
                }
            }
            return true;
        };
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
