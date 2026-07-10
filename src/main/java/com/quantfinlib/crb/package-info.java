/**
 * The central risk book — one netted view of the firm's risk across
 * desks and products, and the machinery that monetizes it.
 *
 * <p>A CRB exists because two desks paying the street to shed opposite
 * risks is money burned twice. The flow here: every instrument —
 * cash equities, listed equity options, FX spot, FX swaps, NDFs, FX
 * options — decomposes into a COMMON factor space at booking
 * ({@link com.quantfinlib.crb.CentralRiskBook}: equity deltas per
 * symbol, currency-level FX deltas so spot/swap/NDF/option legs net,
 * dollar gamma and vega per underlying, forward-points risk).
 * Incoming flow is priced with an inventory skew
 * ({@link com.quantfinlib.crb.SkewedQuoter}) and either internalized
 * or routed ({@link com.quantfinlib.crb.InternalizationEngine}:
 * risk-reducing flow earns price improvement, risk-adding flow is
 * warehoused only inside the limit). The residual is watched by
 * {@link com.quantfinlib.crb.CrbAutoHedger} (band breach → hedge the
 * EXCESS only) through {@link com.quantfinlib.crb.HedgeOptimizer}
 * (minimum variance with an L1 cost term — expensive instruments get
 * exactly zero) over a {@link com.quantfinlib.crb.CrbHedgeUniverse}
 * (the loadings-matrix builder), and hedge orders route through
 * {@link com.quantfinlib.crb.CrbRouter}: internal cross first (the
 * book is the firm's best dark pool), adverse-selection-priced dark
 * venues second, lit last. {@link com.quantfinlib.crb.CrbPnlLedger}
 * keeps the score: did the captured spread pay for the hedging?
 *
 * <p>Everything is deterministic, single-threaded, research/warm lane
 * (interval cadence — the ULL lanes live in {@code trading} and
 * {@code marketdata}). {@code CentralRiskBook.report} hands the netted
 * book to {@link com.quantfinlib.risk.VarEngine} for VaR/ES and prices
 * the diversification benefit of running risk centrally.</p>
 */
package com.quantfinlib.crb;
