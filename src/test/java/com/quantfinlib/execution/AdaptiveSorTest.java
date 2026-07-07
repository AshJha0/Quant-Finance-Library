package com.quantfinlib.execution;

import com.quantfinlib.execution.AdaptiveSor.RoutingDecision;
import com.quantfinlib.execution.SmartOrderRouter.RouteLeg;
import com.quantfinlib.orderbook.Side;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptiveSorTest {

    private static final long US = 1_000L;    // one microsecond in nanos

    private static long litFor(RoutingDecision d, String venue) {
        return d.lit().stream().filter(l -> l.venue().equals(venue))
                .mapToLong(RouteLeg::quantity).sum();
    }

    // ------------------------------------------------------------------
    // The worked example from the spec
    // ------------------------------------------------------------------

    @Test
    void theClassicExampleRoutesBFirstThenAAndProbesDark() {
        // Exchange A: 10,000 @ 120µs, Exchange B: 8,000 @ 80µs (SAME price),
        // Dark Pool: unknown. Expected: 8,000 to B, 2,000 to A, dark probe.
        VenueScorecard card = new VenueScorecard(3);
        AdaptiveSor sor = new AdaptiveSor(card);
        sor.register("A", 0);
        sor.register("B", 1);
        sor.register("DARK", 2);

        List<VenueQuote> venues = List.of(
                new VenueQuote("A", 99.98, 0, 100.00, 10_000, 0, 120 * US, false),
                new VenueQuote("B", 99.98, 0, 100.00, 8_000, 0, 80 * US, false),
                new VenueQuote("DARK", 99.99, 5_000, 100.01, 5_000, 0, 200 * US, true));

        RoutingDecision d = sor.route(Side.BUY, 10_000, venues);
        assertEquals(10_000, d.routedQty());
        assertEquals(0, d.unrouted());
        // Same price, so lower latency wins the first fill: B fully, then A.
        assertEquals(8_000, litFor(d, "B"));
        assertEquals(2_000, litFor(d, "A"));
        assertEquals("B", d.lit().get(0).venue(), "B must be the first (cheaper-expected) leg");
        // A simultaneous dark probe, at the pool's midpoint, contingent.
        assertEquals(1, d.probes().size());
        RouteLeg probe = d.probes().get(0);
        assertEquals("DARK", probe.venue());
        assertTrue(probe.dark());
        assertEquals(100.00, probe.price(), 1e-9);       // (99.99+100.01)/2
        assertEquals(5_000, probe.quantity(), "default probe, dark-capped at 50% of 10k");
    }

    // ------------------------------------------------------------------
    // Each checklist dimension in isolation
    // ------------------------------------------------------------------

    @Test
    void feesAndRebatesReorderEqualQuotes() {
        VenueScorecard card = new VenueScorecard(2);
        AdaptiveSor sor = new AdaptiveSor(card);
        sor.register("MAKER", 0);
        sor.register("TAKER", 1);
        List<VenueQuote> venues = List.of(
                // Same displayed ask, same latency; MAKER rebates (−fee).
                new VenueQuote("MAKER", 0, 0, 100.00, 5_000, -0.2, 50 * US, false),
                new VenueQuote("TAKER", 0, 0, 100.00, 5_000, 0.3, 50 * US, false));
        RoutingDecision d = sor.route(Side.BUY, 5_000, venues);
        assertEquals("MAKER", d.lit().get(0).venue(), "the rebate venue is cheaper all-in");
    }

    @Test
    void latencyBreaksTiesAndReliabilityDiscountsThenVetoes() {
        VenueScorecard card = new VenueScorecard(2, 0.5, 0.95);
        AdaptiveSor sor = new AdaptiveSor(card);
        sor.register("FAST", 0);
        sor.register("SLOW", 1);
        List<VenueQuote> venues = List.of(
                new VenueQuote("FAST", 0, 0, 100.00, 5_000, 0, 50 * US, false),
                new VenueQuote("SLOW", 0, 0, 100.00, 5_000, 0, 500 * US, false));
        // Equal price+fee: the faster venue is cheaper-expected.
        assertEquals("FAST", sor.route(Side.BUY, 1_000, venues).lit().get(0).venue());

        // Now hammer FAST's fill rate below the 0.5 floor: it gets vetoed.
        for (int i = 0; i < 20; i++) {
            card.onMiss(0, 50 * US);
        }
        RoutingDecision d = sor.route(Side.BUY, 1_000, venues);
        assertEquals("SLOW", d.lit().get(0).venue(), "an unreliable venue is vetoed");
    }

    @Test
    void fillProbabilityDiscountsAModestlyUnreliableVenue() {
        // Two venues, equal quote/latency; the flakier one (but above the
        // veto floor) should lose on expected cost.
        VenueScorecard card = new VenueScorecard(2, 0.5, 0.95);
        AdaptiveSor sor = new AdaptiveSor(card, new AdaptiveSor.Config(
                50, 0, 0.3, 5_000, 0.5));   // big miss penalty, no latency term, low veto
        sor.register("RELIABLE", 0);
        sor.register("FLAKY", 1);
        for (int i = 0; i < 40; i++) {
            card.onFill(0, 100 * US);                    // RELIABLE ~1.0
            if (i % 2 == 0) card.onFill(1, 100 * US); else card.onMiss(1, 100 * US); // FLAKY ~0.5
        }
        List<VenueQuote> venues = List.of(
                new VenueQuote("RELIABLE", 0, 0, 100.00, 5_000, 0, 100 * US, false),
                new VenueQuote("FLAKY", 0, 0, 100.00, 5_000, 0, 100 * US, false));
        assertEquals("RELIABLE", sor.route(Side.BUY, 1_000, venues).lit().get(0).venue());
    }

    // ------------------------------------------------------------------
    // Hidden liquidity learning
    // ------------------------------------------------------------------

    @Test
    void darkProbeSizeIsLearnedFromRealizedFills() {
        VenueScorecard card = new VenueScorecard(1, 0.5, 0.95);
        AdaptiveSor sor = new AdaptiveSor(card);
        sor.register("DARK", 0);
        List<VenueQuote> venues = List.of(
                new VenueQuote("DARK", 99.99, 1, 100.01, 1, 0, 200 * US, true));
        // Unknown pool: default probe (5,000), capped at 50% of 6,000 = 3,000.
        assertEquals(3_000, sor.route(Side.BUY, 6_000, venues).probes().get(0).quantity());
        // Learn that this pool typically fills ~1,200 shares.
        for (int i = 0; i < 30; i++) {
            card.onDarkProbe(0, 1_200);
        }
        assertEquals(1_200, sor.route(Side.BUY, 100_000, venues).probes().get(0).quantity(),
                "probe size tracks learned hidden liquidity");
    }

    @Test
    void darkOnlyBookRoutesNoLitButStillProbes() {
        VenueScorecard card = new VenueScorecard(1);
        AdaptiveSor sor = new AdaptiveSor(card);
        sor.register("DARK", 0);
        List<VenueQuote> venues = List.of(
                new VenueQuote("DARK", 99.99, 5_000, 100.01, 5_000, 0, 200 * US, true));
        RoutingDecision d = sor.route(Side.BUY, 4_000, venues);
        assertEquals(0, d.routedQty());
        assertEquals(4_000, d.unrouted());
        assertEquals(1, d.probes().size());
        assertEquals(2_000, d.probes().get(0).quantity());   // capped at 50% of 4,000
    }

    // ------------------------------------------------------------------
    // Queue position leg
    // ------------------------------------------------------------------

    @Test
    void passiveFillProbabilityFollowsQueuePosition() {
        // Front of the queue fills more often than the back.
        double front = AdaptiveSor.passiveFillProbability(0, 1_000, 10_000);
        double back = AdaptiveSor.passiveFillProbability(50_000, 1_000, 10_000);
        assertTrue(front > back);
        assertTrue(front <= 1.0 && back >= 0.0);
    }

    // ------------------------------------------------------------------
    // Cross-asset: the same router on FX venues
    // ------------------------------------------------------------------

    @Test
    void fxEcnRoutingWorksOnRawRatesWithAMidMatchPool() {
        // FX venues quote raw rates (doubles); a mid-match session is the
        // FX "dark pool". Same router, no equities assumptions.
        VenueScorecard card = new VenueScorecard(3);
        AdaptiveSor sor = new AdaptiveSor(card);
        sor.register("EBS", 0);
        sor.register("LSEG", 1);
        sor.register("MIDMATCH", 2);
        List<VenueQuote> venues = List.of(
                new VenueQuote("EBS", 1.08500, 5_000_000, 1.08502, 5_000_000,
                        0, 300 * US, false),
                new VenueQuote("LSEG", 1.08500, 5_000_000, 1.08502, 8_000_000,
                        0, 150 * US, false),
                new VenueQuote("MIDMATCH", 1.08500, 0, 1.08502, 0,
                        0, 400 * US, true));
        RoutingDecision d = sor.route(Side.BUY, 10_000_000, venues);
        assertEquals(10_000_000, d.routedQty());
        assertEquals("LSEG", d.lit().get(0).venue(), "same rate: lower latency first");
        assertEquals(8_000_000, litFor(d, "LSEG"));
        assertEquals(2_000_000, litFor(d, "EBS"));
        assertEquals(1, d.probes().size());
        assertEquals(1.08501, d.probes().get(0).price(), 1e-9, "mid-match fills at mid");
    }

    // ------------------------------------------------------------------
    // Sells + validation
    // ------------------------------------------------------------------

    @Test
    void sellSideSweepsBidsBestFirst() {
        VenueScorecard card = new VenueScorecard(2);
        AdaptiveSor sor = new AdaptiveSor(card);
        sor.register("A", 0);
        sor.register("B", 1);
        List<VenueQuote> venues = List.of(
                new VenueQuote("A", 100.00, 3_000, 0, 0, 0, 100 * US, false),
                new VenueQuote("B", 100.02, 3_000, 0, 0, 0, 100 * US, false));
        RoutingDecision d = sor.route(Side.SELL, 4_000, venues);
        assertEquals("B", d.lit().get(0).venue(), "highest bid first on a sell");
        assertEquals(3_000, litFor(d, "B"));
        assertEquals(1_000, litFor(d, "A"));
    }

    @Test
    void aVenueIsNotPunishedForItsFirstSuccessfulFill() {
        // Regression: EWMAs must seed from the prior/first observation, not
        // ramp from 0 — otherwise a venue's first fill records fillRate 0.05
        // and the router vetoes the venue that just proved it works.
        VenueScorecard card = new VenueScorecard(2, 0.05, 0.95);
        card.onFill(0, 100 * US);
        assertTrue(card.fillRate(0) > 0.9, "one fill must not crater the rate: " + card.fillRate(0));
        assertEquals(100 * US, card.measuredLatencyNanos(0), 1e-6,
                "latency seeds from the first observation, not 5% of it");

        // And the router keeps routing to it (not vetoed).
        AdaptiveSor sor = new AdaptiveSor(card);
        sor.register("PROVEN", 0);
        sor.register("UNTRIED", 1);
        java.util.List<VenueQuote> venues = java.util.List.of(
                new VenueQuote("PROVEN", 0, 0, 100.00, 5_000, 0, 100 * US, false),
                new VenueQuote("UNTRIED", 0, 0, 100.01, 5_000, 0, 100 * US, false));
        assertEquals("PROVEN", sor.route(Side.BUY, 1_000, venues).lit().get(0).venue());
    }

    @Test
    void darkProbeLearningSeedsFromFirstProbe() {
        VenueScorecard card = new VenueScorecard(1, 0.05, 0.95);
        card.onDarkProbe(0, 10_000);
        assertEquals(10_000, card.expectedHiddenShares(0), 1e-6,
                "first probe seeds the estimate, doesn't ramp from 0");
    }

    @Test
    void scorecardUpdatesAreAllocationFree() {
        VenueScorecard card = new VenueScorecard(16, 0.05, 0.95);
        long blackhole = 0;
        for (int i = 0; i < 200_000; i++) {            // warm-up
            blackhole += scoreStep(card, i);
        }
        var mx = (com.sun.management.ThreadMXBean)
                java.lang.management.ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long before = mx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < 500_000; i++) {
            blackhole += scoreStep(card, i);
        }
        long allocated = mx.getThreadAllocatedBytes(tid) - before;
        assertTrue(allocated < 100_000, "scorecard allocated " + allocated + " bytes");
        assertTrue(blackhole != 0);
    }

    private static long scoreStep(VenueScorecard card, int i) {
        int v = i & 15;
        if ((i & 3) == 0) {
            card.onMiss(v, 100 * US + (i % 50));
        } else {
            card.onFill(v, 90 * US + (i % 40));
        }
        if ((i & 7) == 0) {
            card.onDarkProbe(v, 1_000 + (i % 500));
        }
        return (long) (card.fillRate(v) * 1000) + (long) card.measuredLatencyNanos(v);
    }

    @Test
    void configAndRegistrationValidate() {
        VenueScorecard card = new VenueScorecard(2);
        assertThrows(IllegalArgumentException.class,
                () -> new AdaptiveSor.Config(-1, 1, 0.5, 5_000, 0.5));
        assertThrows(IllegalArgumentException.class,
                () -> new AdaptiveSor.Config(1, 1, 1.5, 5_000, 0.5));
        AdaptiveSor sor = new AdaptiveSor(card);
        assertThrows(IllegalArgumentException.class, () -> sor.register("X", 5));
        assertThrows(IllegalArgumentException.class, () -> new VenueScorecard(0));
    }
}
