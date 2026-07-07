/**
 * Multi-day persistence of learned state:
 * {@link com.quantfinlib.persist.Checkpoint} — one binary file of named
 * sections, written at end of day (atomic temp-then-rename, so a crash
 * mid-save never corrupts yesterday's file) and restored at session start.
 *
 * <p>The models that learn across days expose
 * {@code writeState(DataOutput)} / {@code readState(DataInput)} pairs:
 * the seasonality trio ({@code microstructure.VolumeCurve},
 * {@code VolatilityCurve}, {@code SpreadForecaster}),
 * {@code microstructure.OnlineAlphaLearner} (weights AND the out-of-sample
 * IC evidence — restored trust must be earned trust),
 * {@code microstructure.LeadLagEstimator},
 * {@code microstructure.EwmaCovariance} (the basket risk matrix),
 * {@code microstructure.KylesLambda} (learned depth),
 * {@code microstructure.ClosingAuctionModel} (learned auction share), and the
 * venue-quality cards ({@code execution.VenueScorecard} — format v2 with
 * fill markouts, still reads v1 — and {@code fx.LpScorecard}).
 * Intraday state resets on read; configuration (bucket/venue counts) must
 * match or the read throws. {@code HiddenLiquidityDetector} is deliberately
 * NOT persistable: its state is keyed by price level, and overnight the
 * price ladder moves — restoring it would attribute yesterday's icebergs
 * to today's unrelated levels.</p>
 */
package com.quantfinlib.persist;
