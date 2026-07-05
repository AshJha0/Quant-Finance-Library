/**
 * Monte Carlo simulation:
 * {@link com.quantfinlib.simulation.MonteCarloSimulator} runs GBM scenarios
 * (single portfolio or correlated multi-asset via Cholesky) in parallel
 * across cores, deterministic per seed;
 * {@link com.quantfinlib.simulation.SimulationResult} provides the outcome
 * analytics — probability of profit/loss, VaR/CVaR, confidence intervals,
 * best/worst/expected/median terminal values.
 */
package com.quantfinlib.simulation;
