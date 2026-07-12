/**
 * Market analytics across the public/private divide.
 * {@link com.quantfinlib.markets.IndexConstruction} is the arithmetic of
 * benchmarks — cap/price/equal weighting, divisor continuity through
 * membership changes (a member swap must not move the level — pinned),
 * and the one-way turnover between weight vectors that drives tracking
 * cost. {@link com.quantfinlib.markets.PrivateMarketAnalytics} is the
 * toolkit for the asset class with no daily prices: money-weighted IRR
 * (bracket-checked), TVPI/DPI/RVPI multiples, the Kaplan-Schoar
 * public-market equivalent (the only fair index comparison for
 * manager-timed cash flows), and Geltner desmoothing to undo the
 * appraisal smoothing that makes private risk look artificially low
 * next to public markets. Research lane, deterministic.
 */
package com.quantfinlib.markets;
