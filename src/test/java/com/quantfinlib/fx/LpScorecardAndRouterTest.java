package com.quantfinlib.fx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LpScorecardAndRouterTest {

    private static final long MS = 1_000_000L;

    // ------------------------------------------------------------------
    // Scorecard
    // ------------------------------------------------------------------

    @Test
    void rejectRateTracksRecentBehavior() {
        LpScorecard c = new LpScorecard(2, 0.5, 100 * MS);
        assertEquals(0, c.rejectRate(0), 1e-12);
        c.onFill(0, true, 1.08502, 1.08501, MS);
        assertEquals(0, c.rejectRate(0), 1e-12);
        c.onReject(0, true, 1.08501, 0, 2 * MS);
        assertEquals(0.5, c.rejectRate(0), 1e-12);
        c.onReject(0, true, 1.08501, 0, 2 * MS);
        assertEquals(0.75, c.rejectRate(0), 1e-12);
        c.onFill(0, true, 1.08502, 1.08501, MS);
        assertEquals(0.375, c.rejectRate(0), 1e-12);
        assertEquals(4, c.attempts(0));
        assertEquals(2, c.fills(0));
        assertEquals(2, c.rejects(0));
        // LP1 untouched.
        assertEquals(0, c.attempts(1));
    }

    @Test
    void postRejectMarkoutMaturesAtTheHorizon() {
        LpScorecard c = new LpScorecard(1, 1.0, 100 * MS);   // alpha 1: exact values
        // Buy rejected at mid 1.08500; 100ms later mid is 1.08510: +10 pips
        // of markout — the reject cost us the move we were chasing.
        c.onReject(0, true, 1.08500, 0, MS);
        c.onMid(1.08505, 50 * MS);                           // too early: pending
        assertEquals(0, c.postRejectMarkout(0), 1e-12);
        c.onMid(1.08510, 100 * MS);
        assertEquals(0.00010, c.postRejectMarkout(0), 1e-9);
        // A sell reject with the market falling is equally adverse (+).
        c.onReject(0, false, 1.08510, 200 * MS, MS);
        c.onMid(1.08495, 300 * MS);
        assertEquals(0.00015, c.postRejectMarkout(0), 1e-9);
    }

    @Test
    void rejectBurstsAreSampledNotOverwritten() {
        // Bursts happen when the market runs — exactly when markouts are
        // largest — so the pending ring must mature EVERY burst reject,
        // not just the last one.
        LpScorecard c = new LpScorecard(1, 1.0, 100 * MS);
        c.onReject(0, true, 1.08500, 0, MS);
        c.onReject(0, true, 1.08508, 60 * MS, MS);           // within the horizon
        c.onMid(1.08512, 160 * MS);                          // matures BOTH
        assertEquals(2, c.maturedMarkouts());
        // EWMA at alpha=1 ends on the last matured (ring order): +0.00004.
        assertEquals(0.00004, c.postRejectMarkout(0), 1e-9);
    }

    @Test
    void nanReferenceMidsNeverStartAMarkout() {
        // The other NaN door: a reject whose midAtRequest is NaN (one-sided
        // composite at request time) must count against the rate but never
        // create a pending markout — maturing it would poison the EWMA and
        // silently de-route the LP forever.
        LpScorecard c = new LpScorecard(1, 1.0, 100 * MS);
        c.onReject(0, true, Double.NaN, 0, MS);
        assertEquals(1, c.rejects(0), "the reject itself still counts");
        c.onMid(1.08510, 200 * MS);
        assertEquals(0, c.maturedMarkouts(), "no pending markout was created");
        assertEquals(0.0, c.postRejectMarkout(0), 1e-12);
        // And the LP remains routable on real stats afterwards.
        c.onReject(0, true, 1.08500, 300 * MS, MS);
        c.onMid(1.08505, 400 * MS);
        assertEquals(0.00005, c.postRejectMarkout(0), 1e-9);
    }

    @Test
    void nanMidsNeverPoisonTheMarkout() {
        // A one-sided composite produces NaN mids; maturing against one
        // would set the EWMA to NaN forever and disable the router penalty.
        LpScorecard c = new LpScorecard(1, 1.0, 100 * MS);
        c.onReject(0, true, 1.08500, 0, MS);
        c.onMid(Double.NaN, 200 * MS);                       // ignored
        assertEquals(0, c.maturedMarkouts());
        c.onMid(1.08510, 300 * MS);                          // real mid matures it
        assertEquals(0.00010, c.postRejectMarkout(0), 1e-9);
        assertEquals(1, c.maturedMarkouts());
    }

    @Test
    void poisonedOrUnquotedExpectedPricesNeverCaptureTheRouter() {
        // Regression: a NaN expected price for the first candidate must not
        // win the empty-best branch and freeze routing onto that LP.
        FxTierBook b = twoLpBook();
        LpScorecard c = new LpScorecard(2, 1.0, 100 * MS);
        LpRouter r = new LpRouter(b, c, 1.0);
        // LP0's ladder is pulled: its full-amount price is NaN.
        b.tierCount(0, false, 0);
        assertEquals(1, r.route(true, 1_000_000), "NaN candidate must lose");
        assertEquals(1.08504, r.lastQuotedPrice(), 1e-12);
    }

    @Test
    void effectiveSpreadAndHoldAreEwmas() {
        LpScorecard c = new LpScorecard(1, 1.0, 100 * MS);
        c.onFill(0, true, 1.08503, 1.08501, 5 * MS);         // paid 2 pips over mid
        assertEquals(0.00002, c.effectiveSpread(0), 1e-9);
        assertEquals(5 * MS, c.avgHoldNanos(0), 1e-6);
        c.onFill(0, false, 1.08499, 1.08501, 3 * MS);        // sell 2 pips under mid
        assertEquals(0.00002, c.effectiveSpread(0), 1e-9);
        assertEquals(3 * MS, c.avgHoldNanos(0), 1e-6);
    }

    // ------------------------------------------------------------------
    // Router
    // ------------------------------------------------------------------

    private static FxTierBook twoLpBook() {
        FxTierBook b = new FxTierBook(2, 2);
        b.tier(0, false, 0, 1.08502, 5_000_000);             // LP0: tighter ask
        b.tierCount(0, false, 1);
        b.tier(0, true, 0, 1.08499, 5_000_000);
        b.tierCount(0, true, 1);
        b.tier(1, false, 0, 1.08504, 5_000_000);             // LP1: 2 pips wider
        b.tierCount(1, false, 1);
        b.tier(1, true, 0, 1.08497, 5_000_000);
        b.tierCount(1, true, 1);
        return b;
    }

    @Test
    void cleanBooksRouteToTheTightestQuote() {
        FxTierBook b = twoLpBook();
        LpScorecard c = new LpScorecard(2);
        LpRouter r = new LpRouter(b, c, 0.25);
        assertEquals(0, r.route(true, 1_000_000));
        assertEquals(1.08502, r.lastQuotedPrice(), 1e-12);
        assertEquals(1.08502, r.lastExpectedPrice(), 1e-12);
        assertEquals(0, r.route(false, 1_000_000), "LP0 has the better bid too");
    }

    @Test
    void rejectyLpLosesDespiteTheTighterQuote() {
        FxTierBook b = twoLpBook();
        LpScorecard c = new LpScorecard(2, 1.0, 100 * MS);
        // LP0 always rejects (rate 1.0 at alpha 1) with 30 pips of adverse
        // markout: expected LP0 ask = 1.08502 + 1.0 × 0.0030 = 1.08532,
        // worse than LP1's firm 1.08504 despite the tighter display.
        c.onReject(0, true, 1.08500, 0, MS);
        c.onMid(1.08530, 100 * MS);
        LpRouter r = new LpRouter(b, c, 1.0);                // no veto: pure pricing
        assertEquals(1, r.route(true, 1_000_000));
        assertEquals(1.08504, r.lastQuotedPrice(), 1e-12);
    }

    @Test
    void rejectRateCapVetoesOutright() {
        FxTierBook b = twoLpBook();
        LpScorecard c = new LpScorecard(2, 1.0, 100 * MS);
        c.onReject(0, true, 1.08500, 0, MS);                 // rate -> 1.0
        LpRouter r = new LpRouter(b, c, 0.25);
        assertEquals(1, r.route(true, 1_000_000));
        assertTrue(r.vetoCount() > 0);
        // Both vetoed/unquoting -> -1 and NaN prices.
        c.onReject(1, true, 1.08500, 0, MS);
        assertEquals(-1, r.route(true, 1_000_000));
        assertTrue(Double.isNaN(r.lastQuotedPrice()));
    }

    @Test
    void holdTimeIsPricedLikeLatencyWhenUrgencyIsSet() {
        // Two LPs, identical quotes and zero rejects; LP0 holds requests
        // 50ms, LP1 decides in 1ms. With hold urgency, the slow holder
        // loses the tie — FX's latency dimension, priced.
        FxTierBook b = new FxTierBook(2, 1);
        for (int lp = 0; lp < 2; lp++) {
            b.tier(lp, false, 0, 1.08502, 5_000_000);
            b.tierCount(lp, false, 1);
        }
        LpScorecard c = new LpScorecard(2, 1.0, 100 * MS);
        c.onFill(0, true, 1.08502, 1.08501, 50 * MS);
        c.onFill(1, true, 1.08502, 1.08501, MS);
        // Without urgency: a pure price tie (first candidate wins).
        assertEquals(0, new LpRouter(b, c, 1.0).route(true, 1_000_000));
        // With urgency: the fast decider wins.
        LpRouter urgent = new LpRouter(b, c, 1.0, 1.0);   // 1 bp per ms held
        assertEquals(1, urgent.route(true, 1_000_000));
        assertTrue(urgent.lastExpectedPrice() > urgent.lastQuotedPrice(),
                "the hold penalty must be visible in the expected price");
    }

    @Test
    void routerRespectsClipSizeAgainstTiers() {
        FxTierBook b = twoLpBook();
        LpScorecard c = new LpScorecard(2);
        LpRouter r = new LpRouter(b, c, 0.5);
        assertEquals(-1, r.route(true, 20_000_000), "nobody quotes 20M full-amount");
        assertEquals(0, r.route(true, 5_000_000));
    }

    @Test
    void scorecardAndRoutingAreAllocationFree() {
        FxTierBook b = twoLpBook();
        LpScorecard c = new LpScorecard(2);
        // Positive hold urgency so the proof covers the hold-penalty branch
        // of the routing loop, not just the urgency-disabled configuration.
        LpRouter r = new LpRouter(b, c, 0.9, 1.0);
        long blackhole = 0;
        for (int i = 0; i < 200_000; i++) {                  // warm-up
            blackhole += step(b, c, r, i);
        }
        var mx = (com.sun.management.ThreadMXBean)
                java.lang.management.ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long before = mx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < 500_000; i++) {
            blackhole += step(b, c, r, i);
        }
        long allocated = mx.getThreadAllocatedBytes(tid) - before;
        assertTrue(allocated < 100_000, "LP path allocated " + allocated + " bytes");
        assertTrue(blackhole != 0);
    }

    private static long step(FxTierBook b, LpScorecard c, LpRouter r, int i) {
        double px = 1.08500 + (i % 5) * 1e-5;
        b.tier(i % 2, false, 0, px + 2e-5, 5_000_000);
        b.tierCount(i % 2, false, 1);
        long t = i * 1_000L;
        if ((i & 7) == 0) {
            c.onReject(i % 2, true, px, t, MS);
        } else {
            c.onFill(i % 2, true, px + 2e-5, px + 1e-5, MS);
        }
        c.onMid(px, t + 200 * MS);
        return r.route(true, 1_000_000);
    }
}
