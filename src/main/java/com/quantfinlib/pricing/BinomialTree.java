package com.quantfinlib.pricing;

import com.quantfinlib.pricing.BlackScholes.OptionType;

/**
 * Cox-Ross-Rubinstein binomial tree for European and American options with a
 * continuous carry yield (dividends / foreign rate). Converges to
 * Black-Scholes for European payoffs; prices the early-exercise premium for
 * American ones.
 */
public final class BinomialTree {

    public enum ExerciseStyle {
        EUROPEAN, AMERICAN
    }

    private BinomialTree() {
    }

    public static double price(OptionType type, ExerciseStyle style, double spot, double strike,
                               double rate, double carry, double vol, double timeYears, int steps) {
        if (steps < 1) {
            throw new IllegalArgumentException("steps must be >= 1");
        }
        if (timeYears <= 0) {
            return BlackScholes.intrinsic(type, spot, strike);
        }
        double dt = timeYears / steps;
        double u = Math.exp(vol * Math.sqrt(dt));
        double d = 1 / u;
        double growth = Math.exp((rate - carry) * dt);
        double p = (growth - d) / (u - d);
        if (p <= 0 || p >= 1) {
            throw new IllegalArgumentException(
                    "degenerate tree (p=" + p + "): increase steps or check inputs");
        }
        double discount = Math.exp(-rate * dt);

        // Terminal payoffs.
        double[] values = new double[steps + 1];
        for (int j = 0; j <= steps; j++) {
            double s = spot * Math.pow(u, j) * Math.pow(d, steps - j);
            values[j] = BlackScholes.intrinsic(type, s, strike);
        }
        // Backward induction with optional early exercise.
        for (int i = steps - 1; i >= 0; i--) {
            for (int j = 0; j <= i; j++) {
                double continuation = discount * (p * values[j + 1] + (1 - p) * values[j]);
                if (style == ExerciseStyle.AMERICAN) {
                    double s = spot * Math.pow(u, j) * Math.pow(d, i - j);
                    values[j] = Math.max(continuation, BlackScholes.intrinsic(type, s, strike));
                } else {
                    values[j] = continuation;
                }
            }
        }
        return values[0];
    }

    /** Early-exercise premium: American price minus European price. */
    public static double earlyExercisePremium(OptionType type, double spot, double strike,
                                              double rate, double carry, double vol,
                                              double timeYears, int steps) {
        return price(type, ExerciseStyle.AMERICAN, spot, strike, rate, carry, vol, timeYears, steps)
                - price(type, ExerciseStyle.EUROPEAN, spot, strike, rate, carry, vol, timeYears, steps);
    }

    /** Delta from the first tree step (central difference at t=dt). */
    public static double delta(OptionType type, ExerciseStyle style, double spot, double strike,
                               double rate, double carry, double vol, double timeYears, int steps) {
        double h = spot * 1e-4;
        double up = price(type, style, spot + h, strike, rate, carry, vol, timeYears, steps);
        double dn = price(type, style, spot - h, strike, rate, carry, vol, timeYears, steps);
        return (up - dn) / (2 * h);
    }
}
