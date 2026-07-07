/**
 * Market microstructure analytics:
 * {@link com.quantfinlib.microstructure.MarketImpactModel} (square-root law
 * + Almgren-Chriss temporary/permanent decomposition),
 * {@link com.quantfinlib.microstructure.AlmgrenChriss} (closed-form optimal
 * execution trajectories on the cost/risk frontier),
 * {@link com.quantfinlib.microstructure.QueueModel} (fill probability from
 * queue position and latency advantage),
 * {@link com.quantfinlib.microstructure.TransactionCostAnalyzer}
 * (implementation shortfall, slippage vs VWAP, effective spread per fill),
 * {@link com.quantfinlib.microstructure.TickSizeSchedule} (banded price
 * grids) and {@link com.quantfinlib.microstructure.Auction} (call uncross).
 *
 * <p>Streaming, hot-lane (zero allocation per event):
 * {@link com.quantfinlib.microstructure.SignalEngine} — the unified
 * multi-symbol signal engine for equities and FX (imbalance, microprice,
 * volatility, liquidity, momentum, weighted composite) — built on
 * {@link com.quantfinlib.microstructure.FlowSignals} (Cont-Kukanov OFI,
 * queue and trade imbalance) with
 * {@link com.quantfinlib.microstructure.CircuitBreakers} (LULD bands and
 * limit states, market-wide halt levels) guarding the equity side.</p>
 *
 * <p>Quant models feeding the execution algos
 * ({@code execution.BenchmarkExecutor.MarketState}):
 * {@link com.quantfinlib.microstructure.VolumeCurve} (dynamic intraday
 * volume prediction — learned profile + intraday rescale, the live VWAP
 * curve), {@link com.quantfinlib.microstructure.QueuePositionEstimator}
 * (L2 queue position via pro-rata cancel attribution),
 * {@link com.quantfinlib.microstructure.HiddenLiquidityDetector}
 * (lit-tape iceberg / hidden-size inference),
 * {@link com.quantfinlib.microstructure.SpreadForecaster} (time-of-day
 * spread baseline + mean-reverting deviation),
 * {@link com.quantfinlib.microstructure.VolatilityCurve} (intraday vol
 * seasonality; its {@code regime()} is the normalized volatility input the
 * benchmark executor documents),
 * {@link com.quantfinlib.microstructure.TradeClassifier} (Lee-Ready
 * aggressor inference for feeds without an aggressor flag) and
 * {@link com.quantfinlib.microstructure.FillProbabilityModel}
 * (touch × queue passive-fill probability). Their batch/ML siblings
 * live in {@code ml} (volatility forecasting, market-impact prediction,
 * intraday liquidity) and {@code pricing.FairValueEngine} (microprice
 * short-term alpha).</p>
 *
 * <p>Adaptive layer on top of the models:
 * {@link com.quantfinlib.microstructure.OnlineAlphaLearner} (online
 * ridge-SGD from the SignalEngine ingredients to next-interval returns,
 * gated by a prequential out-of-sample IC so a learner that found noise
 * emits no signal),
 * {@link com.quantfinlib.microstructure.LeadLagEstimator} (streaming
 * cross-asset lead-lag correlation — EURUSD leads EURJPY, futures lead
 * cash — with a regression prediction at the best lag) and
 * {@link com.quantfinlib.microstructure.DayTypeProfiles} (per-day-type
 * seasonality: expiry days, half days and FX fixing days each learn their
 * own volume/vol/spread curves) and
 * {@link com.quantfinlib.microstructure.EwmaCovariance} (streaming
 * RiskMetrics-style covariance matrix — marginal basket risk for
 * {@code execution.PortfolioExecutor}, live min-variance hedge ratios),
 * {@link com.quantfinlib.microstructure.KylesLambda} (impact LEARNED from
 * the tape: streaming Δp-on-signed-flow regression, the live producer for
 * {@code MarketState.impactBps}),
 * {@link com.quantfinlib.microstructure.JumpRobustVolatility} (bipower
 * variation: a headline print is a jump, not a volatility regime) and
 * {@link com.quantfinlib.microstructure.ClosingAuctionModel}
 * (auction share + imbalance-tilted reserve — shipped as a
 * documented-contract structure, see its javadoc caveat).</p>
 */
package com.quantfinlib.microstructure;
