package com.quantfinlib.rfq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The RFQ market structure (moved from core {@code pricing.AutocallableRfqTest} in 4.0). */
class RfqMarketStructureTest {

    // ------------------------------------------------------------------
    // RfqAuction — best, cover, spread to fair
    // ------------------------------------------------------------------

    @Test
    void bestAndCoverFollowTheClientsDirection() {
        RfqAuction buy = new RfqAuction(true, 1_000_000, 3, 0);
        buy.onQuote(0, 1_012_000, 100);
        buy.onQuote(1, 1_008_000, 150);
        buy.onQuote(2, 1_015_000, 120);
        assertEquals(1, buy.winner(), "client buys: lowest price wins");
        assertEquals(1_008_000, buy.bestPrice(), 1e-9);
        assertEquals(1_012_000, buy.coverPrice(), 1e-9, "second-best is the cover");
        assertEquals(80, buy.winnerSpreadToFairBps(), 1e-9, "paid 80 bps over model");

        RfqAuction sell = new RfqAuction(false, 1_000_000, 3, 0);
        sell.onQuote(0, 988_000, 100);
        sell.onQuote(1, 992_000, 150);
        assertEquals(1, sell.winner(), "client sells: highest price wins");
        assertEquals(988_000, sell.coverPrice(), 1e-9);
        assertEquals(80, sell.winnerSpreadToFairBps(), 1e-9, "received 80 bps under model");
    }

    @Test
    void declinesRefreshesAndAnUnanchoredAuction() {
        RfqAuction rfq = new RfqAuction(true, Double.NaN, 2, 1_000);
        assertEquals(-1, rfq.winner());
        assertTrue(Double.isNaN(rfq.coverPrice()));
        rfq.onQuote(0, 1_010_000, 2_000);
        rfq.onQuote(0, 1_005_000, 3_000);                  // refresh: last PRICE stands
        assertEquals(1, rfq.quoteCount());
        assertEquals(1_005_000, rfq.bestPrice(), 1e-9);
        assertEquals(1_000, rfq.responseNanos(0),
                "response time is the FIRST show-up, not the refresh");
        rfq.onQuote(1, Double.NaN, 4_000);                 // a decline is not a level
        assertEquals(1, rfq.quoteCount());
        rfq.onQuote(0, 0, 5_000);                          // an explicit pull withdraws
        assertEquals(0, rfq.quoteCount());
        assertTrue(Double.isNaN(rfq.winnerSpreadToFairBps()), "no anchor, no spread");
    }

    // ------------------------------------------------------------------
    // RfqDealerScorecard — who deserves tomorrow's panel
    // ------------------------------------------------------------------

    @Test
    void thePanelLearnsWhoShowsUpAndHowTight() {
        RfqDealerScorecard card = new RfqDealerScorecard(2, 0.5);
        for (int i = 0; i < 6; i++) {
            RfqAuction rfq = new RfqAuction(true, 1_000_000, 2, 0);
            rfq.onQuote(0, 1_005_000, 1_000);              // TIGHT: always, 50 bps over
            if (i % 2 == 0) {
                rfq.onQuote(1, 1_020_000, 5_000);          // WIDE: half the time, 200 bps
            }
            card.onAuction(rfq);
        }
        assertEquals(1.0, card.quoteRate(0), 1e-9, "dealer 0 always shows up");
        assertTrue(card.quoteRate(1) < 0.8, "dealer 1 misses half the requests");
        assertEquals(1.0, card.winRate(0), 1e-9, "and dealer 0 wins every time");
        assertEquals(0.0, card.winRate(1), 1e-9);
        assertEquals(50, card.avgSpreadToFairBps(0), 1e-9, "seeded exactly at first obs");
        assertEquals(200, card.avgSpreadToFairBps(1), 1e-9);
        assertEquals(1_000, card.avgResponseNanos(0), 1e-9);
        assertEquals(6, card.requests(0));
        assertEquals(3, card.quotesGiven(1));

        RfqAuction mismatched = new RfqAuction(true, 1_000_000, 3, 0);
        assertThrows(IllegalArgumentException.class, () -> card.onAuction(mismatched));
        assertThrows(IllegalArgumentException.class, () -> new RfqDealerScorecard(0));
    }

    @Test
    void rfqUpdatesAreAllocationFree() {
        // The repo convention: every zero-alloc javadoc claim carries this
        // proof. One long-lived auction is hammered with quotes/pulls and
        // scored repeatedly — auction reads and card updates allocate nothing.
        RfqAuction rfq = new RfqAuction(true, 1_000_000, 8, 0);
        RfqDealerScorecard card = new RfqDealerScorecard(8, 0.05);
        double blackhole = 0;
        for (int i = 0; i < 200_000; i++) {            // warm-up
            blackhole += rfqStep(rfq, card, i);
        }
        var mx = (com.sun.management.ThreadMXBean)
                java.lang.management.ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long before = mx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < 500_000; i++) {
            blackhole += rfqStep(rfq, card, i);
        }
        long allocated = mx.getThreadAllocatedBytes(tid) - before;
        assertTrue(allocated < 100_000, "rfq machinery allocated " + allocated + " bytes");
        assertTrue(blackhole != 0);
    }

    private static double rfqStep(RfqAuction rfq, RfqDealerScorecard card, int i) {
        int d = i & 7;
        rfq.onQuote(d, 1_000_000 + (i % 1_000) * 10, i);
        if ((i & 15) == 0) {
            rfq.onQuote((d + 1) & 7, 0, i);            // the pull path stays hot too
        }
        card.onAuction(rfq);
        return rfq.bestPrice() + rfq.quoteCount() + rfq.winnerSpreadToFairBps()
                + card.quoteRate(d) + card.avgSpreadToFairBps(d) + card.winRate(d);
    }
}
