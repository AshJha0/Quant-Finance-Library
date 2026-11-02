package com.quantfinlib.fx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Multi-venue aggregation: composite BBO with venue attribution, venue
 * pulls, crossed-composite reporting, sweepable-size sums, and the
 * zero-allocation guarantee on the quote path.
 */
class AggregatedBookTest {

    @Test
    void compositeTracksBestAcrossVenues() {
        AggregatedBook book = new AggregatedBook(3);
        book.onQuote(0, 1.08500, 1_000_000, 1.08520, 1_000_000);
        book.onQuote(1, 1.08505, 2_000_000, 1.08515, 500_000);  // best both sides
        book.onQuote(2, 1.08490, 5_000_000, 1.08530, 3_000_000);

        assertEquals(1.08505, book.bestBid());
        assertEquals(1.08515, book.bestAsk());
        assertEquals(1, book.bestBidVenue());
        assertEquals(1, book.bestAskVenue());
        assertEquals(2_000_000, book.bestBidSize());
        assertEquals(500_000, book.bestAskSize());
        assertEquals((1.08505 + 1.08515) / 2, book.mid(), 1e-12);
        assertEquals(1.08515 - 1.08505, book.spread(), 1e-12);
        assertFalse(book.isCrossed());
        assertEquals(3, book.updateCount());
        assertEquals(3, book.venueCount());
    }

    @Test
    void pullingTheBestVenuePromotesTheNext() {
        AggregatedBook book = new AggregatedBook(2);
        book.onQuote(0, 1.0850, 1_000_000, 1.0852, 1_000_000);
        book.onQuote(1, 1.0851, 2_000_000, 1.0853, 2_000_000);
        assertEquals(1, book.bestBidVenue());
        // Venue 1 disconnects: venue 0 must own the composite again.
        book.clear(1);
        assertEquals(0, book.bestBidVenue());
        assertEquals(1.0850, book.bestBid());
        assertEquals(1.0852, book.bestAsk());
        // Empty book: NaN composite, no venue, zero sizes.
        book.clear(0);
        assertTrue(Double.isNaN(book.bestBid()));
        assertEquals(-1, book.bestBidVenue());
        assertEquals(0, book.bestBidSize());
        assertEquals(0, book.totalBidSizeAtBest(0));
    }

    @Test
    void crossedCompositesAreReportedNotHidden() {
        AggregatedBook book = new AggregatedBook(2);
        // Venue 1's stale bid crosses venue 0's fresh ask — real e-FX life.
        book.onQuote(0, 1.0850, 1_000_000, 1.0852, 1_000_000);
        book.onQuote(1, 1.0853, 1_000_000, 1.0855, 1_000_000);
        assertTrue(book.isCrossed());
        assertEquals(1.0853, book.bestBid());
        assertEquals(1.0852, book.bestAsk());
        assertTrue(book.spread() < 0);
    }

    @Test
    void sweepableSizeSumsVenuesWithinTolerance() {
        AggregatedBook book = new AggregatedBook(3);
        book.onQuote(0, 1.08500, 1_000_000, 1.08520, 1_000_000);
        book.onQuote(1, 1.08500, 2_000_000, 1.08521, 500_000);
        book.onQuote(2, 1.08490, 4_000_000, 1.08540, 100_000);
        // Exactly at best: venues 0 and 1 on the bid.
        assertEquals(3_000_000, book.totalBidSizeAtBest(0), 1e-9);
        // One pip of tolerance pulls venue 2 in too.
        assertEquals(7_000_000, book.totalBidSizeAtBest(0.0001), 1e-9);
        // Ask side: half-pip tolerance covers 1.08520 and 1.08521.
        assertEquals(1_500_000, book.totalAskSizeAtBest(0.00005), 1e-9);
    }

    @Test
    void oneSidedQuotesParticipateOnTheirSideOnly() {
        AggregatedBook book = new AggregatedBook(2);
        book.onQuote(0, 1.0850, 1_000_000, Double.NaN, 0); // bid-only venue
        book.onQuote(1, Double.NaN, 0, 1.0853, 2_000_000); // ask-only venue
        assertEquals(1.0850, book.bestBid());
        assertEquals(1.0853, book.bestAsk());
        assertEquals(0, book.bestBidVenue());
        assertEquals(1, book.bestAskVenue());
        assertFalse(book.isCrossed());
    }

    @Test
    void quotePathIsAllocationFree() {
        AggregatedBook book = new AggregatedBook(8);
        for (int v = 0; v < 8; v++) {
            book.onQuote(v, 1.0850 - v * 1e-5, 1_000_000, 1.0852 + v * 1e-5, 1_000_000);
        }
        var mx = (com.sun.management.ThreadMXBean)
                java.lang.management.ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        // Warm up the JIT before measuring.
        for (int i = 0; i < 100_000; i++) {
            book.onQuote(i & 7, 1.0850 + (i % 13) * 1e-6, 1e6, 1.0852 + (i % 7) * 1e-6, 1e6);
        }
        long before = mx.getThreadAllocatedBytes(tid);
        double blackhole = 0;
        for (int i = 0; i < 500_000; i++) {
            book.onQuote(i & 7, 1.0850 + (i % 13) * 1e-6, 1e6, 1.0852 + (i % 7) * 1e-6, 1e6);
            blackhole += book.mid() + book.totalBidSizeAtBest(1e-5);
        }
        long allocated = mx.getThreadAllocatedBytes(tid) - before;
        assertTrue(allocated < 100_000,
                "quote path allocated " + allocated + " bytes (blackhole " + blackhole + ")");
    }

    @Test
    void validation() {
        assertThrows(IllegalArgumentException.class, () -> new AggregatedBook(0));
    }
}
