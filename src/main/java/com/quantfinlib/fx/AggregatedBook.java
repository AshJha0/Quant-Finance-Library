package com.quantfinlib.fx;

/**
 * Multi-venue aggregated top-of-book — the core e-FX data structure: each
 * liquidity provider / ECN streams its own two-sided quote, and the
 * aggregator maintains the composite best bid/offer with venue attribution.
 *
 * <p>Designed for the hot path:</p>
 * <ul>
 *   <li><b>Zero allocation</b> — all state lives in primitive arrays sized
 *       at construction; {@link #onQuote} and every query touch only
 *       primitives.</li>
 *   <li><b>Single writer</b> — one thread (the feed/aggregation thread)
 *       calls {@link #onQuote}; queries from that same thread are exact.
 *       This mirrors the library's SPSC discipline: fan multiple venue
 *       feeds into one aggregation thread (e.g. via the bus) rather than
 *       locking here.</li>
 *   <li><b>Linear rescan</b> — venue counts in e-FX are small (5–30);
 *       rescanning a primitive array on each update is faster and simpler
 *       than maintaining a heap, and it makes venue removal (a NaN quote)
 *       trivially correct.</li>
 * </ul>
 *
 * <p>A venue with no quote (or an explicitly {@link #clear cleared} one)
 * holds NaN and never wins the scan. Crossed composites (bid ≥ ask across
 * venues) are real in e-FX — latency between feeds — and are reported via
 * {@link #isCrossed()} rather than "fixed" silently.</p>
 */
public final class AggregatedBook {

    private final int venueCount;
    private final double[] bids;
    private final double[] bidSizes;
    private final double[] asks;
    private final double[] askSizes;

    // Composite best, refreshed on every quote update.
    private double bestBid = Double.NaN;
    private double bestAsk = Double.NaN;
    private double bestBidSize;
    private double bestAskSize;
    private int bestBidVenue = -1;
    private int bestAskVenue = -1;
    private long updateCount;

    public AggregatedBook(int venueCount) {
        if (venueCount <= 0) {
            throw new IllegalArgumentException("venueCount must be > 0");
        }
        this.venueCount = venueCount;
        this.bids = new double[venueCount];
        this.bidSizes = new double[venueCount];
        this.asks = new double[venueCount];
        this.askSizes = new double[venueCount];
        java.util.Arrays.fill(bids, Double.NaN);
        java.util.Arrays.fill(asks, Double.NaN);
    }

    /**
     * A venue's fresh two-sided quote (NaN on a side pulls that side).
     * Single-writer; refreshes the composite in the same call.
     */
    public void onQuote(int venue, double bid, double bidSize, double ask, double askSize) {
        bids[venue] = bid;
        bidSizes[venue] = bidSize;
        asks[venue] = ask;
        askSizes[venue] = askSize;
        rescan();
        updateCount++;
    }

    /** Pulls a venue entirely (disconnect, last-look withdrawal). */
    public void clear(int venue) {
        onQuote(venue, Double.NaN, 0, Double.NaN, 0);
    }

    /** Recomputes the composite: highest bid, lowest ask, with attribution. */
    private void rescan() {
        double bb = Double.NaN;
        double ba = Double.NaN;
        int bbV = -1;
        int baV = -1;
        for (int v = 0; v < venueCount; v++) {
            double b = bids[v];
            // NaN comparisons are false, so empty venues lose automatically.
            if (!Double.isNaN(b) && (Double.isNaN(bb) || b > bb)) {
                bb = b;
                bbV = v;
            }
            double a = asks[v];
            if (!Double.isNaN(a) && (Double.isNaN(ba) || a < ba)) {
                ba = a;
                baV = v;
            }
        }
        bestBid = bb;
        bestAsk = ba;
        bestBidVenue = bbV;
        bestAskVenue = baV;
        bestBidSize = bbV >= 0 ? bidSizes[bbV] : 0;
        bestAskSize = baV >= 0 ? askSizes[baV] : 0;
    }

    // ------------------------------------------------------------------
    // Composite queries (all primitive, all O(1) except the size sums)
    // ------------------------------------------------------------------

    public double bestBid() {
        return bestBid;
    }

    public double bestAsk() {
        return bestAsk;
    }

    /** Size shown by the single venue owning the best bid. */
    public double bestBidSize() {
        return bestBidSize;
    }

    public double bestAskSize() {
        return bestAskSize;
    }

    /** Venue index owning the best bid, −1 when no venue bids. */
    public int bestBidVenue() {
        return bestBidVenue;
    }

    public int bestAskVenue() {
        return bestAskVenue;
    }

    /** Composite mid; NaN until both sides are quoted. */
    public double mid() {
        return 0.5 * (bestBid + bestAsk);
    }

    /** Composite spread; NaN until both sides are quoted. */
    public double spread() {
        return bestAsk - bestBid;
    }

    /**
     * Total size quoted within {@code tolerance} of the best bid across all
     * venues — the sweepable size at the composite level.
     */
    public double totalBidSizeAtBest(double tolerance) {
        if (Double.isNaN(bestBid)) {
            return 0;
        }
        double total = 0;
        for (int v = 0; v < venueCount; v++) {
            if (!Double.isNaN(bids[v]) && bestBid - bids[v] <= tolerance) {
                total += bidSizes[v];
            }
        }
        return total;
    }

    /** Mirror of {@link #totalBidSizeAtBest} for the offer side. */
    public double totalAskSizeAtBest(double tolerance) {
        if (Double.isNaN(bestAsk)) {
            return 0;
        }
        double total = 0;
        for (int v = 0; v < venueCount; v++) {
            if (!Double.isNaN(asks[v]) && asks[v] - bestAsk <= tolerance) {
                total += askSizes[v];
            }
        }
        return total;
    }

    /**
     * Whether the composite is crossed or locked (best bid ≥ best ask):
     * common transiently in aggregated e-FX, and exactly the state
     * arbitrage/SOR logic wants to see, not have hidden.
     */
    public boolean isCrossed() {
        return bestBid >= bestAsk; // NaN on either side → false
    }

    public int venueCount() {
        return venueCount;
    }

    public long updateCount() {
        return updateCount;
    }
}
