/**
 * Statistical learning models for market data, in plain Java. The main
 * classes:
 * {@link com.quantfinlib.ml.GradientBoostedRegressor} (stump boosting),
 * {@link com.quantfinlib.ml.VolatilityForecaster} (forward realized vol +
 * 0-100 risk score), {@link com.quantfinlib.ml.RegimeDetector} (2-state
 * Gaussian Markov-switching model via Baum-Welch EM),
 * {@link com.quantfinlib.ml.MarketImpactPredictor} (learned impact + sweep
 * probability), {@link com.quantfinlib.ml.IntradayLiquidityForecaster}
 * (session volume profiles) and {@link com.quantfinlib.ml.AnomalyDetector}
 * (quote stuffing, price spikes).
 *
 * <p><b>4.0.</b> This package stays in the stable jar in 4.0. The 3.1 design
 * proposed moving it out, but stable packages ({@code execution}) depend on
 * it, and a move would make the module graph cyclic. See
 * docs/DESIGN_4.0.md.</p>
 */
@NullMarked
package com.quantfinlib.ml;

import org.jspecify.annotations.NullMarked;
