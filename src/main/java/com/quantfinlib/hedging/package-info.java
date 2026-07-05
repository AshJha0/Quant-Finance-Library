/**
 * Hedging algorithms across asset classes:
 * {@link com.quantfinlib.hedging.DeltaHedger} (dynamic delta hedging with
 * bands and costs) and {@link com.quantfinlib.hedging.HedgingSimulator}
 * (Monte Carlo hedging-error distributions),
 * {@link com.quantfinlib.hedging.GreekHedger} (delta-gamma-vega
 * neutralization), {@link com.quantfinlib.hedging.OptionsBook} (book-level
 * Greeks, scenario grids, P&amp;L explain),
 * {@link com.quantfinlib.hedging.MinimumVarianceHedge} (optimal ratios,
 * futures sizing), {@link com.quantfinlib.hedging.FxHedger} (exposure
 * netting, forward carry), {@link com.quantfinlib.hedging.PairsHedger}
 * (spread construction, half-life) and
 * {@link com.quantfinlib.hedging.CointegrationTest} (Engle-Granger — the
 * statistical gate before a pairs trade).
 */
package com.quantfinlib.hedging;
