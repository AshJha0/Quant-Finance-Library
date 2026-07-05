/**
 * Statistical learning for markets, all pure Java:
 * {@link com.quantfinlib.ml.GradientBoostedRegressor} (stump boosting),
 * {@link com.quantfinlib.ml.VolatilityForecaster} (forward realized vol +
 * 0-100 risk score), {@link com.quantfinlib.ml.RegimeDetector} (2-state
 * Gaussian Markov-switching model via Baum-Welch EM),
 * {@link com.quantfinlib.ml.MarketImpactPredictor} (learned impact + sweep
 * probability), {@link com.quantfinlib.ml.IntradayLiquidityForecaster}
 * (session volume profiles) and {@link com.quantfinlib.ml.AnomalyDetector}
 * (quote stuffing, price spikes).
 */
package com.quantfinlib.ml;
