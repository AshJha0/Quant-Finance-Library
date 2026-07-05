package com.quantfinlib.microstructure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Price-banded tick sizes (band lookup, directional rounding, the ESMA-style
 * generator) and the call-auction uncross (volume maximization, surplus
 * minimization, reference tie-breaks, market-on-auction orders).
 */
class TickSizeAuctionTest {

    // ------------------------------------------------------------------
    // TickSizeSchedule
    // ------------------------------------------------------------------

    @Test
    void bandLookupPicksTheRightTick() {
        TickSizeSchedule s = TickSizeSchedule.builder()
                .addBand(0, 0.001)
                .addBand(10, 0.005)
                .addBand(100, 0.01)
                .build();
        assertEquals(3, s.bandCount());
        assertEquals(0.001, s.tickFor(5));
        assertEquals(0.005, s.tickFor(10));    // floor is inclusive
        assertEquals(0.005, s.tickFor(99.99));
        assertEquals(0.01, s.tickFor(2_000));  // last band extends upward
    }

    @Test
    void roundingIsDirectionalForPassiveOrders() {
        TickSizeSchedule s = TickSizeSchedule.builder().addBand(0, 0.05).build();
        // A buy must round DOWN (passive), a sell UP.
        assertEquals(10.10, s.roundDown(10.13), 1e-9);
        assertEquals(10.15, s.roundUp(10.13), 1e-9);
        assertEquals(10.15, s.roundNearest(10.13), 1e-9);
        // On-tick prices stay put in every direction (no FP drift).
        assertEquals(10.10, s.roundDown(10.10), 1e-9);
        assertEquals(10.10, s.roundUp(10.10), 1e-9);
        assertTrue(s.isOnTick(10.10));
        assertTrue(!s.isOnTick(10.13));
    }

    @Test
    void esmaStyleScalesByLiquidityBandAndPrice() {
        TickSizeSchedule liquid = TickSizeSchedule.esmaStyle(0);
        TickSizeSchedule illiquid = TickSizeSchedule.esmaStyle(2);
        // Finer ticks for more liquid instruments at the same price...
        assertTrue(liquid.tickFor(50) < illiquid.tickFor(50));
        assertEquals(100 * liquid.tickFor(50), illiquid.tickFor(50), 1e-12);
        // ...and coarser ticks as price rises (1-2-5 progression).
        assertTrue(liquid.tickFor(500) > liquid.tickFor(5));
        assertEquals(2 * liquid.tickFor(150), liquid.tickFor(250), 1e-12);
        assertThrows(IllegalArgumentException.class, () -> TickSizeSchedule.esmaStyle(9));
    }

    @Test
    void scheduleValidation() {
        assertThrows(IllegalStateException.class, () -> TickSizeSchedule.builder().build());
        assertThrows(IllegalArgumentException.class, () -> TickSizeSchedule.flat(0));
        assertThrows(IllegalArgumentException.class,
                () -> TickSizeSchedule.builder().addBand(1, 0.01).addBand(1, 0.02).build());
        TickSizeSchedule s = TickSizeSchedule.builder().addBand(1, 0.01).build();
        assertThrows(IllegalArgumentException.class, () -> s.tickFor(0.5)); // below first floor
    }

    // ------------------------------------------------------------------
    // Auction
    // ------------------------------------------------------------------

    @Test
    void uncrossMaximizesExecutableVolume() {
        // Classic textbook book: crossing region 99..101.
        Auction a = new Auction()
                .addBuy(101, 300).addBuy(100, 200).addBuy(99, 100)
                .addSell(99, 250).addSell(100, 200).addSell(101, 200);
        Auction.Result r = a.uncross(100);
        // At 100: demand 500 (limits ≥ 100), supply 450 (limits ≤ 100) → 450.
        // At 99: demand 600, supply 250 → 250. At 101: 300 vs 650 → 300.
        assertEquals(100, r.price());
        assertEquals(450, r.volume());
        assertEquals(50, r.imbalance());   // buy surplus
        assertTrue(r.hasBuySurplus());
    }

    @Test
    void tieBreaksUseSurplusThenReference() {
        // Same volume executable at 100 and 101 — surplus decides.
        Auction a = new Auction()
                .addBuy(101, 100)
                .addSell(100, 100).addSell(101, 150);
        // At 100: min(100, 100) = 100, imbalance 0. At 101: min(100, 250)=100,
        // imbalance −150. Surplus minimization picks 100.
        Auction.Result r = a.uncross(105);
        assertEquals(100, r.price());
        assertEquals(0, r.imbalance());

        // Identical volume and surplus at both candidates → reference decides.
        Auction b = new Auction()
                .addBuy(101, 100).addBuy(100, 0 + 1) // 1-lot to make 100 a candidate
                .addSell(99, 101);
        Auction.Result rb = b.uncross(99.0);
        assertTrue(rb.price() <= 100); // pulled toward the reference side
    }

    @Test
    void marketOrdersAreAlwaysEligible() {
        Auction a = new Auction()
                .addMarketBuy(500)
                .addSell(100, 300).addSell(101, 300);
        Auction.Result r = a.uncross(100);
        // Market buy sweeps: at 101 supply 600 vs demand 500 → 500 matched.
        assertEquals(101, r.price());
        assertEquals(500, r.volume());
        // Market-only book has no candidate price: no uncross.
        assertNull(new Auction().addMarketBuy(10).addMarketSell(10).uncross(100));
    }

    @Test
    void uncrossedBookYieldsNoAuction() {
        Auction a = new Auction().addBuy(99, 100).addSell(101, 100);
        assertNull(a.uncross(100));
        assertThrows(IllegalArgumentException.class, () -> new Auction().addBuy(-1, 10));
        assertThrows(IllegalArgumentException.class, () -> new Auction().addMarketBuy(0));
    }
}
