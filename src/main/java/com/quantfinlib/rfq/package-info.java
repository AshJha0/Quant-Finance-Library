/**
 * RFQ market structure for equity derivatives — structured products have
 * no order book; they trade by request-for-quote against a dealer panel:
 * {@link com.quantfinlib.rfq.RfqAuction} (one auction: best price by the
 * client's direction, the industry-standard cover price, spread to a
 * model fair-value anchor such as
 * {@code pricing.Autocallable.price}) and
 * {@link com.quantfinlib.rfq.RfqDealerScorecard} (streaming per-dealer
 * quality across auctions — quote rate, response time, spread to fair,
 * win rate — the panel-selection input, persistable via
 * {@code persist.Checkpoint}). The RFQ siblings of
 * {@code execution.VenueScorecard} (order-book venues) and
 * {@code fx.LpScorecard} (FX quote streams): three market structures,
 * one learned-counterparty-quality discipline.
 */
package com.quantfinlib.rfq;
