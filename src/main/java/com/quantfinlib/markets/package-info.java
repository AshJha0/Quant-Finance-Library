/**
 * Index construction and private-market performance analytics.
 *
 * <p>{@link com.quantfinlib.markets.IndexConstruction} covers cap, price
 * and equal weighting, divisor continuity through membership changes (a
 * member swap does not move the index level; a test pins this), and the
 * one-way turnover between weight vectors that drives tracking cost.</p>
 *
 * <p>{@link com.quantfinlib.markets.PrivateMarketAnalytics} covers assets
 * with no daily prices: money-weighted IRR with a bracket check,
 * TVPI/DPI/RVPI multiples, the Kaplan-Schoar public-market equivalent
 * (Kaplan and Schoar, 2005), which compares manager-timed cash flows with
 * an index on equal terms, and Geltner (1993) desmoothing, which removes
 * the appraisal smoothing that understates private-market risk.
 * Research lane, deterministic.</p>
 */
package com.quantfinlib.markets;
