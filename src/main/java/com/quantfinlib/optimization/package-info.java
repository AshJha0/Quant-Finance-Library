/**
 * Portfolio construction:
 * {@link com.quantfinlib.optimization.PortfolioOptimizer} (max Sharpe, min
 * volatility, efficient frontier — derivative-free and deterministic),
 * {@link com.quantfinlib.optimization.RiskParityOptimizer} (equal risk
 * contribution), {@link com.quantfinlib.optimization.BlackLitterman}
 * (equilibrium returns blended with confidence-weighted views) and
 * {@link com.quantfinlib.optimization.ConstrainedPortfolioOptimizer}
 * (position caps/floors and turnover penalties against current holdings).
 */
package com.quantfinlib.optimization;
