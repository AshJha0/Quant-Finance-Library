/**
 * Request-for-quote auctions for equity derivatives. Structured products
 * have no order book; they trade by request for quote against a dealer
 * panel:
 * {@link com.quantfinlib.rfq.RfqAuction} (one auction: best price by the
 * client's direction, the industry-standard cover price, spread to a
 * model fair-value anchor such as
 * {@code pricing.Autocallable.price}) and
 * {@link com.quantfinlib.rfq.RfqDealerScorecard} (streaming per-dealer
 * quality across auctions: quote rate, response time, spread to fair,
 * win rate; the panel-selection input, persistable via
 * {@code persist.Checkpoint}). The RFQ siblings of
 * {@code execution.VenueScorecard} (order-book venues) and
 * {@code fx.LpScorecard} (FX quote streams): three market structures,
 * one learned-counterparty-quality discipline.
 *
 * <p><b>Moves in 4.0.</b> This package moves, with its name unchanged, to
 * the reference-tier artifact {@code quant-finance-library-lowlatency}
 * (module {@code com.quantfinlib.lowlatency}): it is an execution workflow,
 * not pricing. Add that dependency; imports do not change. See
 * docs/DESIGN_4.0.md and docs/MIGRATION_4.0.md.</p>
 */
@NullMarked
package com.quantfinlib.rfq;

import org.jspecify.annotations.NullMarked;
