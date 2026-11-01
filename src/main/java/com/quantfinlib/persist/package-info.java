/**
 * Persistence of learned intraday state across trading days.
 * {@link com.quantfinlib.persist.Checkpoint} writes one binary file of
 * named sections at end of day and restores it at session start. The write
 * goes to a temporary file that is forced to disk and renamed, so a crash
 * mid-save leaves the previous file intact.
 *
 * <p>The models that learn across days expose
 * {@code writeState(DataOutput)} / {@code readState(DataInput)} pairs:
 * the seasonality trio ({@code microstructure.VolumeCurve},
 * {@code VolatilityCurve}, {@code SpreadForecaster}),
 * {@code microstructure.OnlineAlphaLearner} (weights and the out-of-sample
 * IC evidence),
 * {@code microstructure.LeadLagEstimator},
 * {@code microstructure.EwmaCovariance} (the basket risk matrix),
 * {@code microstructure.KylesLambda} (learned depth),
 * {@code microstructure.ClosingAuctionModel} (learned auction share),
 * {@code microstructure.AlphaEnsemble} (per-component IC evidence),
 * {@code rfq.RfqDealerScorecard} (learned dealer-panel quality), and the
 * venue-quality cards ({@code execution.VenueScorecard}, format v2 with
 * fill markouts, still reads v1; and {@code fx.LpScorecard}).
 * Intraday state resets on read; configuration (bucket/venue counts) must
 * match or the read throws. {@code HiddenLiquidityDetector} is
 * not persistable: its state is keyed by price level, and overnight the
 * price ladder moves. Restoring it would attribute yesterday's icebergs
 * to today's unrelated levels.</p>
 */
@NullMarked
package com.quantfinlib.persist;

import org.jspecify.annotations.NullMarked;
