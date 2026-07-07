package com.quantfinlib.execution;

import com.quantfinlib.orderbook.Side;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HftSorTest {

    @Test
    void routesByAllInPriceAcrossVenues() {
        HftSor sor = new HftSor(3);
        sor.venueQuote(0, 9_999, 500, 10_001, 300);
        sor.venueQuote(1, 9_998, 400, 10_000, 200);   // best raw ask
        sor.venueQuote(2, 10_000, 100, 10_002, 900);
        long[] out = new long[3];

        long routed = sor.route(Side.BUY, 600, Integer.MAX_VALUE, out);
        assertEquals(600, routed);
        assertEquals(200, out[1]);                     // 10_000 first
        assertEquals(300, out[0]);                     // then 10_001
        assertEquals(100, out[2]);                     // then 10_002

        // Sell sweeps bids from the highest.
        routed = sor.route(Side.SELL, 550, Integer.MIN_VALUE, out);
        assertEquals(550, routed);
        assertEquals(100, out[2]);                     // 10_000
        assertEquals(450, out[0]);                     // 9_999 (only 450 needed)
        assertEquals(0, out[1]);
    }

    @Test
    void feesReorderVenuesAndRebatesAttract() {
        HftSor sor = new HftSor(2);
        sor.venueQuote(0, 0, 0, 10_000, 500);
        sor.venueQuote(1, 0, 0, 10_001, 500);
        sor.fee(0, 3.0);                               // 10_000 + 3 = 10_003 all-in
        sor.fee(1, -0.5);                              // 10_001 - 0.5 = 10_000.5
        long[] out = new long[2];
        sor.route(Side.BUY, 400, Integer.MAX_VALUE, out);
        assertEquals(400, out[1], "the rebate venue must win on all-in price");
        assertEquals(0, out[0]);
    }

    @Test
    void limitPriceExcludesVenuesBeyondIt() {
        HftSor sor = new HftSor(2);
        sor.venueQuote(0, 0, 0, 10_000, 100);
        sor.venueQuote(1, 0, 0, 10_005, 900);
        long[] out = new long[2];
        long routed = sor.route(Side.BUY, 500, 10_002, out);
        assertEquals(100, routed, "the 10_005 venue is through the limit");
        assertEquals(100, out[0]);
        assertEquals(0, out[1]);
    }

    @Test
    void emptyBooksRouteNothing() {
        HftSor sor = new HftSor(2);
        long[] out = new long[2];
        assertEquals(0, sor.route(Side.BUY, 100, Integer.MAX_VALUE, out));
        sor.venueQuote(0, 9_999, 100, 0, 0);           // bid only
        assertEquals(0, sor.route(Side.BUY, 100, Integer.MAX_VALUE, out));
        assertEquals(100, sor.route(Side.SELL, 100, Integer.MIN_VALUE, out));
    }

    @Test
    void routingIsAllocationFree() {
        HftSor sor = new HftSor(8);
        for (int v = 0; v < 8; v++) {
            sor.venueQuote(v, 9_990 + v, 200 + v * 50, 10_010 - v, 200 + v * 30);
            sor.fee(v, (v % 3) - 1.0);
        }
        long[] out = new long[8];
        for (int i = 0; i < 200_000; i++) {            // warm-up
            sor.route((i & 1) == 0 ? Side.BUY : Side.SELL, 500 + (i % 700),
                    (i & 1) == 0 ? Integer.MAX_VALUE : Integer.MIN_VALUE, out);
        }
        var mx = (com.sun.management.ThreadMXBean)
                java.lang.management.ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long before = mx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < 500_000; i++) {
            sor.route((i & 1) == 0 ? Side.BUY : Side.SELL, 500 + (i % 700),
                    (i & 1) == 0 ? Integer.MAX_VALUE : Integer.MIN_VALUE, out);
        }
        long allocated = mx.getThreadAllocatedBytes(tid) - before;
        assertTrue(allocated < 100_000, "routing allocated " + allocated + " bytes");
    }
}
