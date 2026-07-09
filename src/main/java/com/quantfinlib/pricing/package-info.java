/**
 * Fair value and derivatives pricing:
 * {@link com.quantfinlib.pricing.BlackScholes} (Greeks with continuous
 * carry — equities and Garman-Kohlhagen FX — plus implied vol),
 * {@link com.quantfinlib.pricing.BinomialTree} (CRR American/European),
 * {@link com.quantfinlib.pricing.VolSurface} (pillar smiles, total-variance
 * term interpolation), {@link com.quantfinlib.pricing.SabrModel} (Hagan 2002
 * + calibration), {@link com.quantfinlib.pricing.FairValueEngine}
 * (microprice and latency-adjusted true mid),
 * {@link com.quantfinlib.pricing.TriangularArbitrage} (executable FX
 * round-trip edge), {@link com.quantfinlib.pricing.ForwardCurve}
 * (FX forwards with covered-interest-parity checks), the exotics
 * ({@link com.quantfinlib.pricing.DigitalOption},
 * {@link com.quantfinlib.pricing.TouchOption},
 * {@link com.quantfinlib.pricing.BarrierOption},
 * {@link com.quantfinlib.pricing.VannaVolga}) and
 * {@link com.quantfinlib.pricing.Autocallable} (the flagship equity
 * structured product: memory coupons, autocall observations, European
 * knock-in — Monte Carlo with antithetic variates under documented GBM
 * simplifications; RFQ market structure for trading it lives in
 * {@code rfq}). Market-risk pricing models
 * ({@code docs/MARKET_RISK.md} step 4):
 * {@link com.quantfinlib.pricing.Black76} (options on forwards/futures
 * — the convention caps, swaptions and futures options are quoted in),
 * {@link com.quantfinlib.pricing.Heston} (stochastic volatility,
 * semi-analytic little-trap form, MC cross-checked) and
 * {@link com.quantfinlib.pricing.HigherOrderGreeks} (vanna, volga,
 * exchange-option cross-gamma — pinned as finite differences of the
 * first-order Greeks).
 */
package com.quantfinlib.pricing;
