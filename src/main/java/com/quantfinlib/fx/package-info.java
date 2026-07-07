/**
 * FX-specific market machinery — spot conventions through NDFs:
 * {@link com.quantfinlib.fx.CurrencyPair} (pip/precision tables, T+1/T+2
 * spot lags, dual-calendar tenor dates with modified-following and end-end),
 * {@link com.quantfinlib.fx.SwapPointsCurve} (quoted forward points →
 * outrights, broken dates linear in days, covered-interest-parity carry),
 * {@link com.quantfinlib.fx.FxSwap} (near/far legs, points MTM, roll cost),
 * {@link com.quantfinlib.fx.Ndf} (fixing vs settlement lags per restricted
 * currency, USD-settled difference amounts),
 * {@link com.quantfinlib.fx.FxVolSurface} (delta-quoted smiles — ATM DNS,
 * RR/BF wings, premium-adjusted delta↔strike solving),
 * {@link com.quantfinlib.fx.FixingRisk} (WM/R-window TWAP/VWAP tracking
 * error and participation), and the hot-path pieces:
 * {@link com.quantfinlib.fx.AggregatedBook} (zero-alloc multi-venue BBO
 * with venue attribution), {@link com.quantfinlib.fx.CrossRateEngine}
 * (streaming synthetic crosses chained on the bus consumer thread),
 * {@link com.quantfinlib.fx.FxTierBook} (per-LP size-tier ladders:
 * sweep cost and full-amount quotes), {@link com.quantfinlib.fx.LpScorecard}
 * (streaming last-look analytics: reject rate, hold, post-reject markout),
 * {@link com.quantfinlib.fx.LpRouter} (expected-all-in routing that prices
 * rejects into the decision) and {@link com.quantfinlib.fx.SyntheticCross}
 * (direct-vs-legs execution arithmetic with spread composition).
 */
package com.quantfinlib.fx;
