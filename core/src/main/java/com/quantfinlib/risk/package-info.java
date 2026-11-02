/**
 * Risk measurement, decomposition, limits and model validation.
 * Portfolio analytics ({@link com.quantfinlib.risk.RiskMetrics},
 * {@link com.quantfinlib.risk.PortfolioRiskAnalyzer},
 * {@link com.quantfinlib.risk.CorrelationMatrix},
 * {@link com.quantfinlib.risk.RiskMetricRegistry} for custom metrics);
 * pre-trade and credit controls
 * ({@link com.quantfinlib.risk.PreTradeLimitChecker},
 * {@link com.quantfinlib.risk.CounterpartyExposureTracker},
 * {@link com.quantfinlib.risk.SettlementRiskAnalyzer} for Herstatt windows,
 * {@link com.quantfinlib.risk.ConcentrationRisk}); and validation —
 * {@link com.quantfinlib.risk.VarBacktest} (Kupiec, Christoffersen and
 * conditional coverage tests of a VaR series).
 *
 * <p>The market-risk workflow ({@code docs/MARKET_RISK.md}):
 * {@link com.quantfinlib.risk.Dependence} (Spearman, Kendall's τ, the
 * elliptical-copula bridge), {@link com.quantfinlib.risk.Pca} (Jacobi
 * eigen-decomposition; level, slope and curvature factors),
 * {@link com.quantfinlib.risk.GaussianCopula} (Gaussian and Student-t
 * samplers; the t copula has tail dependence, the Gaussian has none), {@link com.quantfinlib.risk.VarEngine} (portfolio
 * delta-normal / Monte Carlo / delta-gamma Cornish-Fisher / historical,
 * each with expected shortfall),
 * {@link com.quantfinlib.risk.ExtremeValueTheory} (POT/GPD tail fits
 * that refuse a finite ES when the tail has no mean),
 * {@link com.quantfinlib.risk.StressTester} (scenarios, ladders, and
 * closed-form reverse stress with an implausibility verdict),
 * {@link com.quantfinlib.risk.FrtbEs} (the 97.5% ES liquidity-horizon
 * cascade, stressed calibration, Basel traffic light — styled after
 * BCBS MAR33, not certified) and
 * {@link com.quantfinlib.risk.PnlAttribution} (the FRTB PLAT:
 * Spearman + Kolmogorov-Smirnov with green/amber/red zones).</p>
 *
 * <p>Regulatory capital: {@link com.quantfinlib.risk.FrtbSa},
 * {@link com.quantfinlib.risk.FrtbDrc}, {@link com.quantfinlib.risk.FrtbRrao}
 * and {@link com.quantfinlib.risk.FrtbNmrf} (FRTB standardised approach and
 * NMRF, styled after BCBS d457, not certified), and
 * {@link com.quantfinlib.risk.SaCcr} with
 * {@link com.quantfinlib.risk.SaCcrHaircuts} (BCBS 279).</p>
 */
@NullMarked
package com.quantfinlib.risk;

import org.jspecify.annotations.NullMarked;
