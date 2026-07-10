package com.quantfinlib.volatility;

import com.quantfinlib.util.MathUtils;

/**
 * HAR-RV (Corsi's Heterogeneous AutoRegressive realized-volatility
 * model) — the forecasting benchmark GARCH papers have to beat, and it
 * is three regressors and an intercept:
 *
 * <pre>  RV_{t+1} = c + β_d·RV_t + β_w·RV̄_t^{(5)} + β_m·RV̄_t^{(22)} + ε</pre>
 *
 * daily, weekly-average and monthly-average realized variance — the
 * "heterogeneous" traders operating at three horizons. Fits by plain
 * OLS on the normal equations (no optimizer), forecasts one step
 * ahead, and floors the forecast at zero (a negative variance forecast
 * is an extrapolation artifact, not a market view).
 *
 * <p>Feed it realized DAILY variance — squared returns summed
 * intraday, or {@code JumpRobustVolatility}'s bipower variance when
 * jumps should not contaminate the forecast (the standard pairing).
 * Static, deterministic, research lane.</p>
 */
public final class HarRv {

    private static final int WEEK = 5;
    private static final int MONTH = 22;

    /** Fitted coefficients: {@code rv⁺ = c + βd·d + βw·w + βm·m}. */
    public record Params(double intercept, double betaDaily, double betaWeekly,
                         double betaMonthly) {
    }

    private HarRv() {
    }

    /**
     * Fits by OLS. Needs enough history for the monthly window plus a
     * meaningful regression sample.
     *
     * @param realizedVariance daily RV series, ≥ 60 finite non-negative
     *                         observations
     */
    public static Params fit(double[] realizedVariance) {
        int n = realizedVariance.length;
        if (n < 60) {
            throw new IllegalArgumentException("need >= 60 daily observations, got " + n);
        }
        for (double rv : realizedVariance) {
            if (!(rv >= 0) || rv == Double.POSITIVE_INFINITY) {
                throw new IllegalArgumentException("realized variance must be >= 0 and finite");
            }
        }
        // Rows t = MONTH-1 .. n-2 predict rv[t+1].
        int rows = n - MONTH;
        double[][] xtx = new double[4][4];
        double[] xty = new double[4];
        double[] x = new double[4];
        for (int t = MONTH - 1; t < n - 1; t++) {
            x[0] = 1;
            x[1] = realizedVariance[t];
            x[2] = MathUtils.mean(realizedVariance, t - WEEK + 1, t + 1);
            x[3] = MathUtils.mean(realizedVariance, t - MONTH + 1, t + 1);
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) {
                    xtx[i][j] += x[i] * x[j];
                }
                xty[i] += x[i] * realizedVariance[t + 1];
            }
        }
        if (rows < 30) {
            throw new IllegalArgumentException("only " + rows + " regression rows");
        }
        double[] beta = MathUtils.solveLinear(xtx, xty);
        return new Params(beta[0], beta[1], beta[2], beta[3]);
    }

    /**
     * One-step-ahead RV forecast from the series' most recent day/week/
     * month, floored at zero.
     */
    public static double forecast(double[] realizedVariance, Params p) {
        int n = realizedVariance.length;
        if (n < MONTH) {
            throw new IllegalArgumentException("need >= " + MONTH + " observations to forecast");
        }
        double d = realizedVariance[n - 1];
        double w = MathUtils.mean(realizedVariance, n - WEEK, n);
        double m = MathUtils.mean(realizedVariance, n - MONTH, n);
        return Math.max(0, p.intercept()
                + p.betaDaily() * d + p.betaWeekly() * w + p.betaMonthly() * m);
    }
}
