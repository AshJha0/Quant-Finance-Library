package com.quantfinlib.ml;

import com.quantfinlib.stats.Stats;

import java.util.ArrayList;
import java.util.List;

/**
 * Machine Learning Risk Forecasting: predicts forward realized volatility from
 * a return series using gradient-boosted trees over engineered features
 * (multi-horizon realized vol, momentum, and shock magnitude), and maps the
 * forecast to a 0-100 risk score.
 *
 * <p>{@link #fit} is in-sample: 150 boosting rounds with no early stopping
 * will fit noise, and {@link #riskScore} ranks against the training targets.
 * Before relying on the model, run {@link #validate}: it fits on the first
 * part of the series and scores out-of-sample forecasts against two
 * baselines the model must beat, RiskMetrics EWMA (λ = 0.94) and HAR-RV on
 * squared returns. Trees are invariant to monotone feature scaling, so no
 * scaling is applied.</p>
 */
public final class VolatilityForecaster {

    private static final int LOOKBACK = 21;

    private final int horizon;
    private final GradientBoostedRegressor model;
    private double[] trainingTargets = new double[0];
    private boolean fitted;

    /** @param horizon forward window (in periods) whose realized vol is predicted */
    public VolatilityForecaster(int horizon) {
        this.horizon = horizon;
        this.model = new GradientBoostedRegressor(150, 0.08);
    }

    public static VolatilityForecaster weekly() {
        return new VolatilityForecaster(5);
    }

    /** Trains on a historical return series (needs at least ~3 months of data). */
    public VolatilityForecaster fit(double[] returns) {
        List<double[]> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        for (int t = LOOKBACK; t + horizon <= returns.length; t++) {
            xs.add(features(returns, t));
            ys.add(Stats.sampleStdDev(returns, t, t + horizon));
        }
        if (xs.size() < 30) {
            throw new IllegalArgumentException(
                    "insufficient history: " + returns.length + " returns for horizon " + horizon);
        }
        double[][] x = xs.toArray(new double[0][]);
        double[] y = new double[ys.size()];
        for (int i = 0; i < y.length; i++) {
            y[i] = ys.get(i);
        }
        model.fit(x, y);
        trainingTargets = y;
        fitted = true;
        return this;
    }

    /** Forecast of next-{@code horizon}-period volatility (per-period units). */
    public double forecast(double[] returns) {
        requireFitted();
        if (returns.length < LOOKBACK) {
            throw new IllegalArgumentException("need at least " + LOOKBACK + " returns");
        }
        return Math.max(0, model.predict(features(returns, returns.length)));
    }

    /**
     * Risk score in [0, 100]: the percentile of the forecast within the
     * realised volatilities of the training sample (an in-sample reference
     * distribution).
     */
    public double riskScore(double[] returns) {
        double f = forecast(returns);
        int below = 0;
        for (double t : trainingTargets) {
            if (t <= f) {
                below++;
            }
        }
        return 100.0 * below / trainingTargets.length;
    }

    /**
     * Out-of-sample comparison: RMSE (per-period vol units) of the model and
     * of the two baselines over the same forecast dates.
     */
    public record Validation(int forecasts, double rmseModel, double rmseEwma, double rmseHar) {

        /** True when the model beats both baselines out of sample. */
        public boolean beatsBaselines() {
            return rmseModel < rmseEwma && rmseModel < rmseHar;
        }
    }

    /**
     * Time-split validation. A fresh model with this forecaster's horizon is
     * fitted on {@code returns[0, split)} with {@code split =
     * floor(trainFraction·n)}; every later date t with a full forward window
     * is then forecast from {@code returns[0, t)} only, as are the EWMA and
     * HAR-RV baselines (HAR fitted on the training squared returns). The
     * target is the sample stdev of {@code returns[t, t+horizon)}. This
     * forecaster's own fitted state is not changed.
     */
    public Validation validate(double[] returns, double trainFraction) {
        if (!(trainFraction > 0 && trainFraction < 1)) {
            throw new IllegalArgumentException("trainFraction must be in (0, 1)");
        }
        int n = returns.length;
        int split = (int) Math.floor(trainFraction * n);
        if (split < 100 || n - split < horizon + 20) {
            throw new IllegalArgumentException("need >= 100 training returns and >= "
                    + (horizon + 20) + " test returns");
        }
        double[] train = java.util.Arrays.copyOf(returns, split);
        VolatilityForecaster m = new VolatilityForecaster(horizon).fit(train);
        double[] sq = new double[n];
        for (int i = 0; i < n; i++) {
            sq[i] = returns[i] * returns[i];
        }
        com.quantfinlib.volatility.HarRv.Params har =
                com.quantfinlib.volatility.HarRv.fit(java.util.Arrays.copyOf(sq, split));
        // EWMA variance for date t from returns before t, bias-corrected.
        double lambda = 0.94;
        double raw = 0;
        double mass = 0;
        double seModel = 0;
        double seEwma = 0;
        double seHar = 0;
        int count = 0;
        for (int t = 1; t + horizon <= n; t++) {
            raw = lambda * raw + (1 - lambda) * sq[t - 1];
            mass = lambda * mass + (1 - lambda);
            if (t < split) {
                continue;
            }
            double target = Stats.sampleStdDev(returns, t, t + horizon);
            double fModel = Math.max(0, m.model.predict(features(returns, t)));
            double fEwma = Math.sqrt(raw / mass);
            double fHar = Math.sqrt(com.quantfinlib.volatility.HarRv.forecast(
                    java.util.Arrays.copyOfRange(sq, t - 22, t), har));
            seModel += (fModel - target) * (fModel - target);
            seEwma += (fEwma - target) * (fEwma - target);
            seHar += (fHar - target) * (fHar - target);
            count++;
        }
        return new Validation(count, Math.sqrt(seModel / count), Math.sqrt(seEwma / count),
                Math.sqrt(seHar / count));
    }

    /** Feature vector computed from returns strictly before index t. */
    private static double[] features(double[] returns, int t) {
        double vol5 = Stats.sampleStdDev(returns, t - 5, t);
        double vol10 = Stats.sampleStdDev(returns, t - 10, t);
        double vol21 = Stats.sampleStdDev(returns, t - LOOKBACK, t);
        double mom5 = Stats.mean(returns, t - 5, t);
        double mom21 = Stats.mean(returns, t - LOOKBACK, t);
        double lastAbs = Math.abs(returns[t - 1]);
        double maxAbs5 = 0;
        for (int i = t - 5; i < t; i++) {
            maxAbs5 = Math.max(maxAbs5, Math.abs(returns[i]));
        }
        return new double[]{vol5, vol10, vol21, mom5, mom21, lastAbs, maxAbs5};
    }

    private void requireFitted() {
        if (!fitted) {
            throw new IllegalStateException("call fit() first");
        }
    }
}
