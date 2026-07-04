package com.quantfinlib.hedging;

import com.quantfinlib.util.MathUtils;

/**
 * Engle-Granger two-step cointegration test: regress one price series on the
 * other, then run an augmented Dickey-Fuller test (no constant) on the
 * residual spread. This is the statistical justification {@link PairsHedger}
 * needs before a pairs trade — a high return correlation is not enough; the
 * spread must be stationary.
 *
 * <p>Critical values are the Engle-Granger two-variable, no-trend
 * asymptotics: -3.90 (1%), -3.34 (5%), -3.04 (10%). More negative ADF
 * t-statistics mean stronger evidence of cointegration.</p>
 */
public final class CointegrationTest {

    public static final double CRITICAL_1PCT = -3.90;
    public static final double CRITICAL_5PCT = -3.34;
    public static final double CRITICAL_10PCT = -3.04;

    public record EngleGrangerResult(
            double hedgeRatio,
            double intercept,
            double adfTStatistic,
            boolean cointegrated1pct,
            boolean cointegrated5pct,
            boolean cointegrated10pct) {
    }

    private CointegrationTest() {
    }

    /** Full Engle-Granger test of prices A against prices B. */
    public static EngleGrangerResult engleGranger(double[] pricesA, double[] pricesB) {
        if (pricesA.length != pricesB.length || pricesA.length < 30) {
            throw new IllegalArgumentException("need two aligned series of >= 30 points");
        }
        // Step 1: cointegrating regression A = a + b*B + e.
        double varB = MathUtils.variance(pricesB);
        double beta = varB == 0 ? 0 : MathUtils.covariance(pricesA, pricesB) / varB;
        double alpha = MathUtils.mean(pricesA) - beta * MathUtils.mean(pricesB);
        double[] residuals = new double[pricesA.length];
        for (int i = 0; i < pricesA.length; i++) {
            residuals[i] = pricesA[i] - beta * pricesB[i] - alpha;
        }
        // Step 2: ADF on the residuals.
        double t = adfTStatistic(residuals);
        return new EngleGrangerResult(beta, alpha, t,
                t < CRITICAL_1PCT, t < CRITICAL_5PCT, t < CRITICAL_10PCT);
    }

    /**
     * Dickey-Fuller t-statistic (no constant, no lags — appropriate for
     * mean-zero regression residuals): regress {@code Δe_t = γ e_{t-1} + u_t}
     * and return {@code t = γ̂ / se(γ̂)}.
     */
    public static double adfTStatistic(double[] series) {
        int n = series.length - 1;
        if (n < 10) {
            throw new IllegalArgumentException("series too short for ADF");
        }
        double sumXy = 0, sumXx = 0;
        for (int i = 1; i <= n; i++) {
            double lag = series[i - 1];
            double diff = series[i] - series[i - 1];
            sumXy += diff * lag;
            sumXx += lag * lag;
        }
        if (sumXx == 0) {
            return 0;
        }
        double gamma = sumXy / sumXx;
        double sse = 0;
        for (int i = 1; i <= n; i++) {
            double resid = (series[i] - series[i - 1]) - gamma * series[i - 1];
            sse += resid * resid;
        }
        double se = Math.sqrt(sse / (n - 1) / sumXx);
        return se == 0 ? 0 : gamma / se;
    }
}
