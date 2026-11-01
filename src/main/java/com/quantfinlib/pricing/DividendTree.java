package com.quantfinlib.pricing;

import com.quantfinlib.pricing.BinomialTree.ExerciseStyle;
import com.quantfinlib.pricing.BlackScholes.OptionType;

/**
 * European and American options on a stock paying discrete cash dividends,
 * on a recombining Cox-Ross-Rubinstein tree, by the method of Vellekoop and
 * Nieuwenhuis (2006, "Efficient pricing of derivatives on assets with
 * discrete dividends", Applied Mathematical Finance 13(3), 265-284).
 *
 * <p>The model is the one traders mean: the stock follows geometric Brownian
 * motion between ex-dates and drops by the cash amount {@code D} at each
 * ex-date. Subtracting D from the nodes would make the tree non-recombining.
 * Vellekoop and Nieuwenhuis keep one recombining lattice of stock levels
 * {@code S_ij = S u^{2j-i}} and move the dividend into the value function
 * instead: at an ex-date step i,</p>
 *
 * <pre>
 *   V_cum(S_ij) = max( exercise(S_ij),  V_ex(S_ij - D) )      (American)
 *   V_cum(S_ij) = V_ex(S_ij - D)                              (European)
 * </pre>
 *
 * <p>where {@code V_ex} is the value just after the drop, known on the same
 * level's nodes and read at {@code S_ij - D} by linear interpolation in S
 * (linear extrapolation above the top node; below the bottom node, linear
 * towards the value at S = 0, which is 0 for a call and K for an American put
 * or {@code K e^{-r(T-t)}} for a European put). The exercise test on the cum
 * price is what captures early exercise of calls just before an ex-date.</p>
 *
 * <p>Conventions: {@code borrow} is a continuous yield (stock borrow fee) on
 * top of the cash dividends, entering like {@code carry} in
 * {@link BinomialTree}. Each ex-date is moved to the nearest tree step, and
 * to step 1 if it rounds to 0, so the date error is at most dt/2; several
 * dividends on one step are summed. A dividend with ex-time equal to expiry
 * is applied to the terminal payoff; dividends after expiry are ignored.
 * With an empty schedule the price equals {@link BinomialTree#price} to rounding
 * (tested). Convergence is first order in dt, like the plain CRR tree.
 * Allocates three arrays of {@code steps + 1} doubles per call.</p>
 */
public final class DividendTree {

    private DividendTree() {
    }

    /**
     * Option price.
     *
     * @param borrow    continuous borrow/yield on top of the cash dividends
     * @param dividends cash dividends; {@link DividendSchedule#NONE} for none
     * @param steps     number of tree steps, at least 1
     */
    public static double price(OptionType type, ExerciseStyle style, double spot, double strike,
                               double rate, double borrow, double vol, double timeYears,
                               DividendSchedule dividends, int steps) {
        if (steps < 1) {
            throw new IllegalArgumentException("steps must be >= 1");
        }
        if (!(spot > 0) || !(strike > 0) || !(vol > 0) || !(timeYears > 0)
                || !Double.isFinite(spot) || !Double.isFinite(strike) || !Double.isFinite(vol)
                || !Double.isFinite(timeYears) || !Double.isFinite(rate)
                || !Double.isFinite(borrow)) {
            throw new IllegalArgumentException("need finite spot, strike, vol, timeYears > 0");
        }
        double dt = timeYears / steps;
        double u = Math.exp(vol * Math.sqrt(dt));
        double d = 1 / u;
        double p = (Math.exp((rate - borrow) * dt) - d) / (u - d);
        if (!(p > 0 && p < 1)) {
            throw new IllegalArgumentException(
                    "degenerate tree (p=" + p + "): increase steps or check inputs");
        }
        double discount = Math.exp(-rate * dt);
        boolean american = style == ExerciseStyle.AMERICAN;

        // Dividend amount per step.
        double[] divAt = new double[steps + 1];
        for (int k = 0; k < dividends.count(); k++) {
            double t = dividends.exTime(k);
            if (t > timeYears) {
                break;
            }
            int idx = (int) Math.max(1, Math.min(steps, Math.round(t / dt)));
            divAt[idx] += dividends.amount(k);
        }

        double[] v = new double[steps + 1];
        for (int j = 0; j <= steps; j++) {
            double s = node(spot, u, steps, j);
            v[j] = BlackScholes.intrinsic(type, Math.max(0, s - divAt[steps]), strike);
        }
        double[] tmp = new double[steps + 1];
        for (int i = steps - 1; i >= 0; i--) {
            double div = divAt[i];
            for (int j = 0; j <= i; j++) {
                double cont = discount * (p * v[j + 1] + (1 - p) * v[j]);
                v[j] = american
                        ? Math.max(cont, BlackScholes.intrinsic(type, node(spot, u, i, j), strike))
                        : cont;
            }
            if (div > 0) {
                double atZero = type == OptionType.CALL ? 0
                        : (american ? strike : strike * Math.exp(-rate * (timeYears - i * dt)));
                for (int j = 0; j <= i; j++) {
                    double s = node(spot, u, i, j);
                    double after = interpolate(v, i, spot, u, s - div, atZero);
                    tmp[j] = american
                            ? Math.max(after, BlackScholes.intrinsic(type, s, strike))
                            : after;
                }
                System.arraycopy(tmp, 0, v, 0, i + 1);
            }
        }
        return v[0];
    }

    private static double node(double spot, double u, int level, int j) {
        return spot * Math.pow(u, 2 * j - level);
    }

    /** Value at stock level x on level i (nodes j = 0..i ascending), see class doc. */
    private static double interpolate(double[] v, int i, double spot, double u, double x,
                                      double atZero) {
        if (x <= 0) {
            return atZero;
        }
        double lo = node(spot, u, i, 0);
        if (x <= lo) {
            return atZero + (v[0] - atZero) * x / lo;
        }
        if (i == 0) {
            return v[0];
        }
        // j such that S_j <= x < S_{j+1}: S_j = spot u^{2j - i}
        int j = (int) Math.floor((Math.log(x / spot) / Math.log(u) + i) / 2);
        j = Math.max(0, Math.min(i - 1, j));
        double s0 = node(spot, u, i, j);
        double s1 = node(spot, u, i, j + 1);
        return v[j] + (v[j + 1] - v[j]) * (x - s0) / (s1 - s0);
    }
}
