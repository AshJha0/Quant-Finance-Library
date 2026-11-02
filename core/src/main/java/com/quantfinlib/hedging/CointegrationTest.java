package com.quantfinlib.hedging;

import com.quantfinlib.stats.LinearAlgebra;
import com.quantfinlib.stats.Stats;

/**
 * Engle-Granger two-step cointegration test: regress one price series on the
 * other, then run an augmented Dickey-Fuller test (no constant) on the
 * residual spread. This is the statistical justification {@link PairsHedger}
 * needs before a pairs trade — a high return correlation is not enough; the
 * spread must be stationary.
 *
 * <p>Critical values: the constants are the Engle-Granger two-variable,
 * no-trend asymptotics -3.90 (1%), -3.34 (5%), -3.04 (10%);
 * {@link #criticalValue} gives MacKinnon's (2010, "Critical values for
 * cointegration tests", Queen's Economics Department WP 1227, Table 2,
 * N = 2, constant) finite-sample response surface
 * {@code τ(T) = τ∞ + τ₁/T + τ₂/T²}, which {@link #engleGranger(double[],
 * double[], int)} uses. More negative ADF t-statistics mean stronger
 * evidence of cointegration. The hedge ratio is the OLS slope of A on B,
 * which is not symmetric in the two series (regressing B on A gives a
 * different ratio); total least squares or Johansen would be.</p>
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

    private static final double[][] MACKINNON = {
            {-3.89644, -10.9519, -22.527},   // 1%
            {-3.33613, -6.1101, -6.823},     // 5%
            {-3.04445, -4.2412, -2.720}};    // 10%

    /**
     * MacKinnon (2010) finite-sample critical value for the two-variable
     * Engle-Granger test with a constant.
     *
     * @param level        0.01, 0.05 or 0.10
     * @param observations T, the number of residuals used in the ADF regression
     */
    public static double criticalValue(double level, int observations) {
        int row = level == 0.01 ? 0 : level == 0.05 ? 1 : level == 0.10 ? 2 : -1;
        if (row < 0 || observations < 10) {
            throw new IllegalArgumentException("level must be 0.01, 0.05 or 0.10 and T >= 10");
        }
        double t = observations;
        return MACKINNON[row][0] + MACKINNON[row][1] / t + MACKINNON[row][2] / (t * t);
    }

    private CointegrationTest() {
    }

    /**
     * Engle-Granger test with no augmentation lags and the asymptotic
     * critical values (the original behaviour); see
     * {@link #engleGranger(double[], double[], int)}.
     */
    public static EngleGrangerResult engleGranger(double[] pricesA, double[] pricesB) {
        return engleGranger(pricesA, pricesB, 0, false);
    }

    /**
     * Engle-Granger test with {@code lags} augmentation lags in the ADF
     * regression and MacKinnon finite-sample critical values.
     */
    public static EngleGrangerResult engleGranger(double[] pricesA, double[] pricesB, int lags) {
        return engleGranger(pricesA, pricesB, lags, true);
    }

    private static EngleGrangerResult engleGranger(double[] pricesA, double[] pricesB, int lags,
                                                   boolean finiteSample) {
        if (pricesA.length != pricesB.length || pricesA.length < 30) {
            throw new IllegalArgumentException("need two aligned series of >= 30 points");
        }
        // Step 1: cointegrating regression A = a + b*B + e.
        double varB = Stats.sampleVariance(pricesB);
        double beta = varB == 0 ? 0 : Stats.sampleCovariance(pricesA, pricesB) / varB;
        double alpha = Stats.mean(pricesA) - beta * Stats.mean(pricesB);
        double[] residuals = new double[pricesA.length];
        for (int i = 0; i < pricesA.length; i++) {
            residuals[i] = pricesA[i] - beta * pricesB[i] - alpha;
        }
        // Step 2: ADF on the residuals.
        double t = adfTStatistic(residuals, lags);
        int used = residuals.length - 1 - lags;
        double c1 = finiteSample ? criticalValue(0.01, used) : CRITICAL_1PCT;
        double c5 = finiteSample ? criticalValue(0.05, used) : CRITICAL_5PCT;
        double c10 = finiteSample ? criticalValue(0.10, used) : CRITICAL_10PCT;
        return new EngleGrangerResult(beta, alpha, t, t < c1, t < c5, t < c10);
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

    /**
     * Augmented Dickey-Fuller t-statistic without constant (the residuals of
     * a regression with intercept have mean zero):
     * {@code Δe_t = γ·e_{t−1} + Σ_{j=1..p} φ_j·Δe_{t−j} + u_t}, OLS over
     * {@code t = p+1 .. n−1}; returns {@code γ̂ / se(γ̂)}. {@code lags = 0}
     * is {@link #adfTStatistic(double[])}.
     */
    public static double adfTStatistic(double[] series, int lags) {
        if (lags == 0) {
            return adfTStatistic(series);
        }
        if (lags < 0) {
            throw new IllegalArgumentException("lags must be >= 0");
        }
        int k = lags + 1;
        int rows = series.length - 1 - lags;
        if (rows < k + 10) {
            throw new IllegalArgumentException("series too short for ADF with " + lags + " lags");
        }
        double[][] xtx = new double[k][k];
        double[] xty = new double[k];
        double[][] x = new double[rows][k];
        double[] y = new double[rows];
        for (int r = 0; r < rows; r++) {
            int t = r + lags + 1;
            y[r] = series[t] - series[t - 1];
            x[r][0] = series[t - 1];
            for (int j = 1; j <= lags; j++) {
                x[r][j] = series[t - j] - series[t - j - 1];
            }
            for (int a = 0; a < k; a++) {
                xty[a] += x[r][a] * y[r];
                for (int b = 0; b < k; b++) {
                    xtx[a][b] += x[r][a] * x[r][b];
                }
            }
        }
        double[][] inv = LinearAlgebra.inverse(xtx);
        double[] beta = LinearAlgebra.matVec(inv, xty);
        double sse = 0;
        for (int r = 0; r < rows; r++) {
            double e = y[r] - LinearAlgebra.dot(x[r], beta);
            sse += e * e;
        }
        double s2 = sse / (rows - k);
        double se = Math.sqrt(s2 * inv[0][0]);
        return se == 0 ? 0 : beta[0] / se;
    }
}
