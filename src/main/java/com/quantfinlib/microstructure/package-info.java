/**
 * Market microstructure analytics: impact, liquidity, order-flow signals
 * and intraday curves. The main classes:
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
 * {@link com.quantfinlib.microstructure.SignalEngine}, a multi-symbol
 * signal engine for equities and FX (imbalance, microprice, volatility,
 * liquidity, momentum, weighted composite), built on
 * {@link com.quantfinlib.microstructure.FlowSignals} (Cont-Kukanov OFI,
 * queue and trade imbalance) with
 * {@link com.quantfinlib.microstructure.CircuitBreakers} (LULD bands and
 * limit states, market-wide halt levels) guarding the equity side.</p>
 *
 * <p>Models feeding the execution algorithms
 * ({@code execution.BenchmarkExecutor.MarketState}):
 * {@link com.quantfinlib.microstructure.VolumeCurve} (intraday volume
 * prediction from a learned profile with intraday rescaling; the VWAP
 * curve), {@link com.quantfinlib.microstructure.QueuePositionEstimator}
 * (L2 queue position via pro-rata cancel attribution),
 * {@link com.quantfinlib.microstructure.HiddenLiquidityDetector}
 * (iceberg and hidden-size inference from the lit tape),
 * {@link com.quantfinlib.microstructure.SpreadForecaster} (time-of-day
 * spread baseline plus a mean-reverting deviation),
 * {@link com.quantfinlib.microstructure.VolatilityCurve} (intraday vol
 * seasonality; its {@code regime()} is the normalized volatility input the
 * benchmark executor documents),
 * {@link com.quantfinlib.microstructure.TradeClassifier} (Lee-Ready
 * aggressor inference for feeds without an aggressor flag) and
 * {@link com.quantfinlib.microstructure.FillProbabilityModel}
 * (touch × queue passive-fill probability). Batch counterparts are in
 * {@code ml} (volatility forecasting, market-impact prediction,
 * intraday liquidity) and {@code pricing.FairValueEngine} (microprice).</p>
 *
 * <p>Adaptive layer on top of the models:
 * {@link com.quantfinlib.microstructure.OnlineAlphaLearner} (online
 * ridge SGD from the SignalEngine inputs to next-interval returns, gated
 * by a prequential out-of-sample IC, so a learner that has found only
 * noise emits no signal),
 * {@link com.quantfinlib.microstructure.LeadLagEstimator} (streaming
 * cross-asset lead-lag correlation, for example EURUSD leading EURJPY or
 * futures leading cash, with a regression prediction at the best lag),
 * {@link com.quantfinlib.microstructure.DayTypeProfiles} (per-day-type
 * seasonality: expiry days, half days and FX fixing days each learn their
 * own volume, volatility and spread curves),
 * {@link com.quantfinlib.microstructure.EwmaCovariance} (streaming
 * RiskMetrics-style covariance matrix, used for marginal basket risk in
 * {@code execution.PortfolioExecutor} and live minimum-variance hedge
 * ratios),
 * {@link com.quantfinlib.microstructure.KylesLambda} (impact estimated
 * from the tape by a streaming regression of price change on signed flow;
 * it produces {@code MarketState.impactBps}),
 * {@link com.quantfinlib.microstructure.JumpRobustVolatility} (bipower
 * variation, so a single large print counts as a jump rather than a change
 * of volatility regime),
 * {@link com.quantfinlib.microstructure.ClosingAuctionModel}
 * (auction share and imbalance-tilted reserve; see the caveat in its
 * javadoc),
 * {@link com.quantfinlib.microstructure.HawkesIntensity} (self-exciting
 * event intensity with stability enforced through the branching ratio)
 * and {@link com.quantfinlib.microstructure.AlphaEnsemble} (IC-weighted
 * blend of alpha components with per-component prequential weights and
 * confidence-sized output).</p>
 *
 * <p>Flow toxicity, mean reversion and bar-only liquidity:
 * {@link com.quantfinlib.microstructure.Vpin} (volume-synchronized
 * probability of informed trading, bucketed in volume time),
 * {@link com.quantfinlib.microstructure.OrnsteinUhlenbeck} (κ, θ and σ by
 * exact AR(1) mapping, half-life and stationary z-score; a series with no
 * in-sample mean reversion is refused) and
 * {@link com.quantfinlib.microstructure.LiquidityMeasures} (Roll's
 * bounce-implied spread, NaN when the signature is absent; Corwin-Schultz
 * high-low spread; Amihud illiquidity) for markets with bars but no
 * quotes. Regime and relationship tracking:
 * {@link com.quantfinlib.microstructure.VarianceRatio} (Lo-MacKinlay test
 * for trending, mean-reverting or random-walk behaviour) and
 * {@link com.quantfinlib.microstructure.KalmanBeta} (time-varying hedge
 * ratio by Kalman filter, with its own uncertainty estimate).</p>
 */
package com.quantfinlib.microstructure;
