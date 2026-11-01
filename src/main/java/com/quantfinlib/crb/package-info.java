/**
 * The central risk book: one netted view of the firm's risk across desks
 * and products, with internalization, routing and hedging.
 *
 * <p>Netting centrally avoids two desks each paying the market to shed
 * opposite risks. Every instrument (cash equities, listed equity options,
 * FX spot, FX swaps, NDFs, FX options) decomposes into a common factor space at booking
 * ({@link com.quantfinlib.crb.CentralRiskBook}: equity deltas per
 * symbol, currency-level FX deltas so spot/swap/NDF/option legs net,
 * dollar gamma and vega per underlying, forward-points risk).
 * Incoming flow is priced with an inventory skew
 * ({@link com.quantfinlib.crb.SkewedQuoter}) and either internalized
 * or routed ({@link com.quantfinlib.crb.InternalizationEngine}:
 * risk-reducing flow earns price improvement, risk-adding flow is
 * warehoused only inside the limit). The residual is watched by
 * {@link com.quantfinlib.crb.CrbAutoHedger} (on a band breach it hedges
 * only the excess) through {@link com.quantfinlib.crb.HedgeOptimizer}
 * (minimum variance with an L1 cost term — expensive instruments get
 * exactly zero) over a {@link com.quantfinlib.crb.CrbHedgeUniverse}
 * (the loadings-matrix builder), and hedge orders route through
 * {@link com.quantfinlib.crb.CrbRouter}: internal cross first,
 * adverse-selection-priced dark venues second, lit last.
 * {@link com.quantfinlib.crb.CrbPnlLedger} records whether the captured
 * spread paid for the hedging.
 *
 * <p>Everything is deterministic, single-threaded, research/warm lane
 * (interval cadence; the low-latency lanes are in {@code trading} and
 * {@code marketdata}). {@code CentralRiskBook.report} hands the netted
 * book to {@link com.quantfinlib.risk.VarEngine} for VaR/ES and prices
 * the diversification benefit of running risk centrally.</p>
 */
@NullMarked
package com.quantfinlib.crb;

import org.jspecify.annotations.NullMarked;
