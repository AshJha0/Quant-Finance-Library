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
 * round-trip edge) and {@link com.quantfinlib.pricing.ForwardCurve}
 * (FX forwards with covered-interest-parity checks).
 */
package com.quantfinlib.pricing;
