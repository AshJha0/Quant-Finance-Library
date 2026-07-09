/**
 * Risk: measurement, decomposition, credit/limits, and model validation.
 * Portfolio analytics ({@link com.quantfinlib.risk.RiskMetrics},
 * {@link com.quantfinlib.risk.PortfolioRiskAnalyzer},
 * {@link com.quantfinlib.risk.CorrelationMatrix},
 * {@link com.quantfinlib.risk.RiskMetricRegistry} for custom metrics);
 * pre-trade and credit controls
 * ({@link com.quantfinlib.risk.PreTradeLimitChecker},
 * {@link com.quantfinlib.risk.CounterpartyExposureTracker},
 * {@link com.quantfinlib.risk.SettlementRiskAnalyzer} for Herstatt windows,
 * {@link com.quantfinlib.risk.ConcentrationRisk}); and validation —
 * {@link com.quantfinlib.risk.VarBacktest} (Kupiec, Christoffersen,
 * conditional coverage) turns produced VaR numbers into validated ones.
 *
 * <p>The market-risk workflow ({@code docs/MARKET_RISK.md}):
 * {@link com.quantfinlib.risk.Dependence} (Spearman, Kendall's τ, the
 * elliptical-copula bridge), {@link com.quantfinlib.risk.Pca} (Jacobi —
 * level/slope/curvature honesty about factor counts),
 * {@link com.quantfinlib.risk.GaussianCopula} (Gaussian + Student-t
 * samplers; the t has the tail dependence 2008 taught everyone the
 * Gaussian lacks), {@link com.quantfinlib.risk.VarEngine} (portfolio
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
 */
package com.quantfinlib.risk;
