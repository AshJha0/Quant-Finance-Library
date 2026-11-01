/**
 * Option and derivatives pricing models, implied volatility and volatility
 * surfaces. The main classes:
 * {@link com.quantfinlib.pricing.BlackScholes} (Greeks with continuous
 * carry for equities and Garman-Kohlhagen FX),
 * {@link com.quantfinlib.pricing.ImpliedVolatility} (Black implied
 * volatility by safeguarded Newton on the normalised Black function of
 * Jäckel, 2015), {@link com.quantfinlib.pricing.Bachelier} (normal model),
 * {@link com.quantfinlib.pricing.QuantoOption} (foreign-asset option paid
 * in domestic currency, with the foreign rate and quanto drift
 * adjustment),
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
 * {@link com.quantfinlib.pricing.Autocallable} (the most common equity
 * structured product: memory coupons, autocall observations, European
 * knock-in; Monte Carlo with antithetic variates under documented GBM
 * simplifications; RFQ trading of it is in {@code rfq}). Market-risk pricing models
 * ({@code docs/MARKET_RISK.md} step 4):
 * {@link com.quantfinlib.pricing.Black76} (options on forwards and
 * futures, the quoting convention for caps, swaptions and futures
 * options),
 * {@link com.quantfinlib.pricing.Heston} (stochastic volatility,
 * semi-analytic little-trap form, MC cross-checked) and
 * {@link com.quantfinlib.pricing.HigherOrderGreeks} (vanna, volga and
 * exchange-option cross-gamma, checked against finite differences of the
 * first-order Greeks).
 */
package com.quantfinlib.pricing;
