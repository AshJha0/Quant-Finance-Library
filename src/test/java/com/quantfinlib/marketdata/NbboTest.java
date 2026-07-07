package com.quantfinlib.marketdata;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NbboTest {

    @Test
    void aggregatesBestAcrossVenuesAndSumsInsideSize() {
        Nbbo n = new Nbbo(3);
        n.onVenueQuote(0, 175_00, 100, 175_02, 200, 1);
        n.onVenueQuote(1, 175_01, 300, 175_03, 50, 2);   // better bid
        n.onVenueQuote(2, 175_01, 200, 175_02, 80, 3);   // ties both sides

        assertEquals(175_01, n.bidTick());
        assertEquals(500, n.bidSize());                   // venues 1 + 2
        assertEquals(0b110, n.bidVenues());
        assertEquals(175_02, n.askTick());
        assertEquals(280, n.askSize());                   // venues 0 + 2
        assertEquals(0b101, n.askVenues());
        assertEquals(175_01.5, n.midTick(), 1e-9);
    }

    @Test
    void venueDownRemovesItsQuotesFromTheInside() {
        Nbbo n = new Nbbo(2);
        n.onVenueQuote(0, 100, 10, 102, 10, 1);
        n.onVenueQuote(1, 101, 20, 103, 20, 2);
        assertEquals(101, n.bidTick());
        assertTrue(n.onVenueDown(1, 3));
        assertEquals(100, n.bidTick());
        assertEquals(102, n.askTick());
        assertTrue(n.onVenueDown(0, 4));
        assertEquals(Nbbo.NO_BID, n.bidTick());
        assertEquals(Nbbo.NO_ASK, n.askTick());
    }

    @Test
    void listenerFiresOnlyOnInsideChanges() {
        Nbbo n = new Nbbo(2);
        long[] fired = {0};
        n.listener((b, bs, a, as, ts) -> fired[0]++);
        n.onVenueQuote(0, 100, 10, 102, 10, 1);
        assertEquals(1, fired[0]);
        // Venue 1 quoting outside the inside: no NBBO change, no callback.
        assertFalse(n.onVenueQuote(1, 99, 5, 103, 5, 2));
        assertEquals(1, fired[0]);
        // Size change AT the inside is a change.
        assertTrue(n.onVenueQuote(0, 100, 15, 102, 10, 3));
        assertEquals(2, fired[0]);
        assertEquals(3, n.updateCount());
        assertEquals(2, n.changeCount());
    }

    @Test
    void lockedAndCrossedMarketsAreFlagged() {
        Nbbo n = new Nbbo(2);
        n.onVenueQuote(0, 100, 10, 100, 10, 1);           // same venue locks
        assertTrue(n.locked());
        assertFalse(n.crossed());
        n.onVenueQuote(1, 101, 10, Nbbo.NO_ASK, 0, 2);    // bid through the offer
        assertTrue(n.crossed());
    }

    @Test
    void midTickSurvivesHighPricedSymbols() {
        // Two ticks near 2^30 (a ~$110k symbol in 0.0001 ticks) must not
        // wrap the 32-bit sum negative.
        Nbbo n = new Nbbo(1);
        n.onVenueQuote(0, 1_100_000_000, 10, 1_100_000_100, 10, 1);
        assertEquals(1_100_000_050.0, n.midTick(), 1e-9);
    }

    @Test
    void offInsideFastPathNeverMissesAnInsideChange() {
        // Randomized differential check: the fast-path-guarded Nbbo must
        // agree with brute-force recomputation after every update.
        Nbbo n = new Nbbo(8);
        int[][] bids = new int[8][2];                  // {tick, size}
        int[][] asks = new int[8][2];
        for (int k = 0; k < 8; k++) {
            bids[k][0] = Nbbo.NO_BID;                  // untouched venue = absent,
            asks[k][0] = Nbbo.NO_ASK;                  // not "quoting at tick 0"
        }
        java.util.SplittableRandom rnd = new java.util.SplittableRandom(11);
        for (int i = 0; i < 20_000; i++) {
            int v = rnd.nextInt(8);
            int bid = 990 + rnd.nextInt(20);
            int ask = bid + 1 + rnd.nextInt(5);
            int bidSz = rnd.nextInt(4) == 0 ? 0 : 1 + rnd.nextInt(500);
            int askSz = rnd.nextInt(4) == 0 ? 0 : 1 + rnd.nextInt(500);
            n.onVenueQuote(v, bid, bidSz, ask, askSz, i);
            bids[v][0] = bidSz > 0 ? bid : Nbbo.NO_BID;
            bids[v][1] = bidSz;
            asks[v][0] = askSz > 0 ? ask : Nbbo.NO_ASK;
            asks[v][1] = askSz;

            int bb = Nbbo.NO_BID;
            long bbSz = 0;
            int bo = Nbbo.NO_ASK;
            long boSz = 0;
            for (int k = 0; k < 8; k++) {
                if (bids[k][0] != Nbbo.NO_BID) {
                    if (bids[k][0] > bb) {
                        bb = bids[k][0];
                        bbSz = bids[k][1];
                    } else if (bids[k][0] == bb) {
                        bbSz += bids[k][1];
                    }
                }
                if (asks[k][0] != Nbbo.NO_ASK) {
                    if (asks[k][0] < bo) {
                        bo = asks[k][0];
                        boSz = asks[k][1];
                    } else if (asks[k][0] == bo) {
                        boSz += asks[k][1];
                    }
                }
            }
            assertEquals(bb, n.bidTick(), "step " + i);
            assertEquals(bbSz, n.bidSize(), "step " + i);
            assertEquals(bo, n.askTick(), "step " + i);
            assertEquals(boSz, n.askSize(), "step " + i);
        }
    }

    @Test
    void venueUpdatesAreAllocationFree() {
        Nbbo n = new Nbbo(8);
        for (int i = 0; i < 200_000; i++) {            // warm-up
            n.onVenueQuote(i & 7, 1000 + (i % 9), 100 + (i % 5), 1002 + (i % 7),
                    100 + (i % 3), i);
        }
        var mx = (com.sun.management.ThreadMXBean)
                java.lang.management.ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long before = mx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < 500_000; i++) {
            n.onVenueQuote(i & 7, 1000 + (i % 9), 100 + (i % 5), 1002 + (i % 7),
                    100 + (i % 3), i);
        }
        long allocated = mx.getThreadAllocatedBytes(tid) - before;
        assertTrue(allocated < 100_000, "NBBO allocated " + allocated + " bytes");
    }

    @Test
    void emptySidesUseSentinels() {
        Nbbo n = new Nbbo(1);
        n.onVenueQuote(0, 100, 10, Nbbo.NO_ASK, 0, 1);
        assertEquals(100, n.bidTick());
        assertEquals(Nbbo.NO_ASK, n.askTick());
        assertTrue(Double.isNaN(n.midTick()));
        // Zero-size sides are treated as absent regardless of price.
        n.onVenueQuote(0, 100, 0, 102, 0, 2);
        assertEquals(Nbbo.NO_BID, n.bidTick());
    }
}
