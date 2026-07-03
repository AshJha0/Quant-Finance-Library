package com.fdequant.ml;

import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MlForecastingTest {

    @Test
    void regressorLearnsStepFunction() {
        SplittableRandom rnd = new SplittableRandom(5);
        int n = 400;
        double[][] x = new double[n][2];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            x[i][0] = rnd.nextDouble();
            x[i][1] = rnd.nextDouble();
            y[i] = x[i][0] > 0.5 ? 2.0 : 1.0;
        }
        GradientBoostedRegressor model = new GradientBoostedRegressor(100, 0.2).fit(x, y);
        assertEquals(2.0, model.predict(new double[]{0.9, 0.5}), 0.05);
        assertEquals(1.0, model.predict(new double[]{0.1, 0.5}), 0.05);
        assertTrue(model.rmse(x, y) < 0.05);
    }

    @Test
    void regressorLearnsAdditiveSignal() {
        SplittableRandom rnd = new SplittableRandom(11);
        int n = 600;
        double[][] x = new double[n][3];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            for (int f = 0; f < 3; f++) {
                x[i][f] = rnd.nextDouble();
            }
            y[i] = 3 * x[i][0] + x[i][1];   // feature 2 is noise
        }
        GradientBoostedRegressor model = GradientBoostedRegressor.withDefaults().fit(x, y);
        assertTrue(model.rmse(x, y) < 0.35);
    }

    @Test
    void volatilityForecasterDetectsRegimeShift() {
        // Low-vol regime followed by high-vol regime; forecast from the
        // high-vol tail should exceed a forecast made in the low-vol regime.
        SplittableRandom rnd = new SplittableRandom(23);
        int n = 700;
        double[] returns = new double[n];
        for (int i = 0; i < n; i++) {
            double vol = i < 500 ? 0.005 : 0.03;
            returns[i] = vol * rnd.nextGaussian();
        }
        VolatilityForecaster f = VolatilityForecaster.weekly().fit(returns);

        double[] calmWindow = java.util.Arrays.copyOfRange(returns, 0, 400);
        double calmForecast = f.forecast(calmWindow);
        double stressForecast = f.forecast(returns);

        assertTrue(stressForecast > calmForecast,
                "stress " + stressForecast + " should exceed calm " + calmForecast);
        double score = f.riskScore(returns);
        assertTrue(score >= 0 && score <= 100);
        assertTrue(score > 50, "regime shift should score above median, got " + score);
    }
}
