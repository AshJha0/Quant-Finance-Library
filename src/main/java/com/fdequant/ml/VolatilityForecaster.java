package com.fdequant.ml;

import com.fdequant.util.MathUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Machine Learning Risk Forecasting: predicts forward realized volatility from
 * a return series using gradient-boosted trees over engineered features
 * (multi-horizon realized vol, momentum, and shock magnitude), and maps the
 * forecast to an intuitive 0-100 risk score.
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
            ys.add(MathUtils.stdDevSample(returns, t, t + horizon));
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
     * Intelligent risk score in [0, 100]: the percentile of the forecast
     * within the distribution of historically realized volatilities.
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

    /** Feature vector computed from returns strictly before index t. */
    private static double[] features(double[] returns, int t) {
        double vol5 = MathUtils.stdDevSample(returns, t - 5, t);
        double vol10 = MathUtils.stdDevSample(returns, t - 10, t);
        double vol21 = MathUtils.stdDevSample(returns, t - LOOKBACK, t);
        double mom5 = MathUtils.mean(returns, t - 5, t);
        double mom21 = MathUtils.mean(returns, t - LOOKBACK, t);
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
