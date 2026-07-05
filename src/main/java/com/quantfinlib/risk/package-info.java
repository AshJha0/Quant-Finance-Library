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
 */
package com.quantfinlib.risk;
