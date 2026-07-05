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
 * with venue attribution) and {@link com.quantfinlib.fx.CrossRateEngine}
 * (streaming synthetic crosses chained on the bus consumer thread).
 */
package com.quantfinlib.fx;
