package com.quantfinlib.volatility;

import com.quantfinlib.stats.Stats;

/**
 * HAR-RV (Corsi's Heterogeneous AutoRegressive realized-volatility
 * model) — the forecasting benchmark GARCH papers have to beat, and it
 * is three regressors and an intercept:
 *
 * <pre>  RV_{t+1} = c + β_d·RV_t + β_w·RV̄_t^{(5)} + β_m·RV̄_t^{(22)} + ε</pre>
 *
 * daily, weekly-average and monthly-average realized variance — the
 * "heterogeneous" traders operating at three horizons (Corsi 2009,
 * J. Financial Econometrics 7, 174-196). Fits by OLS through a
 * Householder QR of the column-scaled design (the normal equations
 * square a condition number that RV in 1e-4 units already makes large),
 * reports Newey-West HAC standard errors, and forecasts one step ahead.
 * {@link #forecast} floors at zero; {@link #fitLog} fits the log-HAR,
 * {@code ln RV_{t+1} = c + β_d ln RV_t + β_w ln RV̄^{(5)} + β_m ln RV̄^{(22)}},
 * which cannot forecast a negative variance and is the better-behaved
 * specification on real data.
 *
 * <p>Feed it realized daily variance — squared returns summed
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

    /**
     * Fitted coefficients with Newey-West standard errors (order: intercept,
     * daily, weekly, monthly), residual variance and the regression size.
     */
    public static final class Fit {
        private final Params params;
        private final double[] hacStandardErrors;
        private final double residualVariance;
        private final int observations;
        private final boolean logModel;

        /**
         * Creates the value; array and collection arguments are copied.
         */
        public Fit(Params params, double[] hacStandardErrors, double residualVariance, int observations,
                boolean logModel) {
            this.params = params;
            this.hacStandardErrors = hacStandardErrors == null ? null : hacStandardErrors.clone();
            this.residualVariance = residualVariance;
            this.observations = observations;
            this.logModel = logModel;
        }

        /** {@code params}. */
        public Params params() {
            return params;
        }

        /** Element {@code i} of {@code hacStandardErrors}. */
        public double hacStandardErrors(int i) {
            return hacStandardErrors[i];
        }

        /** Length of {@code hacStandardErrors}. */
        public int hacStandardErrorsLength() {
            return hacStandardErrors.length;
        }

        /** A fresh copy of {@code hacStandardErrors}. */
        public double[] hacStandardErrorsCopy() {
            return hacStandardErrors == null ? null : hacStandardErrors.clone();
        }

        /** {@code residualVariance}. */
        public double residualVariance() {
            return residualVariance;
        }

        /** {@code observations}. */
        public int observations() {
            return observations;
        }

        /** {@code logModel}. */
        public boolean logModel() {
            return logModel;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Fit o)) {
                return false;
            }
            return java.util.Objects.equals(params, o.params)
                    && java.util.Arrays.equals(hacStandardErrors, o.hacStandardErrors)
                    && Double.compare(residualVariance, o.residualVariance) == 0
                    && observations == o.observations
                    && logModel == o.logModel;
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Objects.hashCode(params);
            h = 31 * h + java.util.Arrays.hashCode(hacStandardErrors);
            h = 31 * h + Double.hashCode(residualVariance);
            h = 31 * h + Integer.hashCode(observations);
            h = 31 * h + Boolean.hashCode(logModel);
            return h;
        }

        @Override
        public String toString() {
            return "Fit[" + "params=" + params + ", " + "hacStandardErrors=" + java.util.Arrays.toString(hacStandardErrors) + ", " + "residualVariance=" + residualVariance + ", " + "observations=" + observations + ", " + "logModel=" + logModel + "]";
        }
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
        return fitWithErrors(realizedVariance).params();
    }

    /** OLS fit of the level HAR with Newey-West standard errors (5 lags). */
    public static Fit fitWithErrors(double[] realizedVariance) {
        check(realizedVariance, false);
        return regress(realizedVariance, false);
    }

    /**
     * Log-HAR: the same regression on {@code ln RV} with regressors
     * {@code ln RV_t}, {@code ln(mean of 5)} and {@code ln(mean of 22)}.
     * Requires strictly positive RV.
     */
    public static Fit fitLog(double[] realizedVariance) {
        check(realizedVariance, true);
        return regress(realizedVariance, true);
    }

    /**
     * One-step forecast of RV from a log-HAR fit:
     * {@code exp(ŷ + ½σ̂²)}, the lognormal mean given the residual variance.
     */
    public static double forecastLog(double[] realizedVariance, Fit fit) {
        if (!fit.logModel()) {
            throw new IllegalArgumentException("fit is not a log-HAR fit");
        }
        int n = realizedVariance.length;
        if (n < MONTH) {
            throw new IllegalArgumentException("need >= " + MONTH + " observations to forecast");
        }
        Params p = fit.params();
        double d = Math.log(realizedVariance[n - 1]);
        double w = Math.log(Stats.mean(realizedVariance, n - WEEK, n));
        double m = Math.log(Stats.mean(realizedVariance, n - MONTH, n));
        double y = p.intercept() + p.betaDaily() * d + p.betaWeekly() * w + p.betaMonthly() * m;
        return Math.exp(y + 0.5 * fit.residualVariance());
    }

    private static void check(double[] rv, boolean positive) {
        int n = rv.length;
        if (n < 60) {
            throw new IllegalArgumentException("need >= 60 daily observations, got " + n);
        }
        for (double v : rv) {
            if (!(positive ? v > 0 : v >= 0) || v == Double.POSITIVE_INFINITY) {
                throw new IllegalArgumentException("realized variance must be "
                        + (positive ? "> 0" : ">= 0") + " and finite");
            }
        }
    }

    private static Fit regress(double[] rv, boolean log) {
        int n = rv.length;
        // Rows t = MONTH-1 .. n-2 predict rv[t+1].
        int rows = n - MONTH;
        double[][] x = new double[rows][4];
        double[] y = new double[rows];
        for (int t = MONTH - 1, i = 0; t < n - 1; t++, i++) {
            double d = rv[t];
            double w = Stats.mean(rv, t - WEEK + 1, t + 1);
            double m = Stats.mean(rv, t - MONTH + 1, t + 1);
            x[i][0] = 1;
            x[i][1] = log ? Math.log(d) : d;
            x[i][2] = log ? Math.log(w) : w;
            x[i][3] = log ? Math.log(m) : m;
            y[i] = log ? Math.log(rv[t + 1]) : rv[t + 1];
        }
        LeastSquares.Result r = LeastSquares.fit(x, y, WEEK);
        double[] b = r.beta();
        return new Fit(new Params(b[0], b[1], b[2], b[3]), r.hacStdErrors(),
                r.residualVariance(), rows, log);
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
        double w = Stats.mean(realizedVariance, n - WEEK, n);
        double m = Stats.mean(realizedVariance, n - MONTH, n);
        return Math.max(0, p.intercept()
                + p.betaDaily() * d + p.betaWeekly() * w + p.betaMonthly() * m);
    }
}
