package com.quantfinlib.dsl;

/**
 * Factory of common {@link Rule}s over indicator arrays. All rules are
 * NaN-safe: a rule is never satisfied while its inputs are in warm-up.
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
