package com.quantfinlib.execution;

import com.quantfinlib.orderbook.Side;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AdaptiveSor latency cap and total dark cap; AntiGamingJitter separation bound. */
class ExecutionP3FixesTest {

    private static final long MS = 1_000_000L;

    @Test
    void latencyPenaltyCapLetsOtherTermsDecide() {
        // FAST: ask 100.05 (5 bps worse), 0 ms. SLOW: ask 100.00, 100 ms at 1 bp/ms = 100 bps.
        List<VenueQuote> venues = List.of(
                new VenueQuote("FAST", 0, 0, 100.05, 1_000, 0, 0, false),
                new VenueQuote("SLOW", 0, 0, 100.00, 1_000, 0, 100 * MS, false));
        AdaptiveSor uncapped = new AdaptiveSor(new VenueScorecard(2));
        assertEquals("FAST", uncapped.route(Side.BUY, 500, venues).lit().getFirst().venue());
        // Capped at 2 bps: SLOW costs 100.00 + 0.02 < 100.05.
        AdaptiveSor capped = new AdaptiveSor(new VenueScorecard(2)).maxLatencyPenaltyBps(2.0);
        assertEquals("SLOW", capped.route(Side.BUY, 500, venues).lit().getFirst().venue());
        // Cap of 6 bps: SLOW costs 100.06 > 100.05.
        AdaptiveSor six = new AdaptiveSor(new VenueScorecard(2)).maxLatencyPenaltyBps(6.0);
        assertEquals("FAST", six.route(Side.BUY, 500, venues).lit().getFirst().venue());
        assertThrows(IllegalArgumentException.class, () -> capped.maxLatencyPenaltyBps(-1));
        assertThrows(IllegalArgumentException.class, () -> capped.maxLatencyPenaltyBps(Double.NaN));
    }

    @Test
    void sellsRankByHighestExpectedProceedsInPriceUnits() {
        // Fee 10 bps on HIGH (bid 100.05): proceeds 100.05 * (1 - 0.001) = 99.94995 < 100.00.
        List<VenueQuote> venues = List.of(
                new VenueQuote("HIGH", 100.05, 1_000, 0, 0, 10, 0, false),
                new VenueQuote("LOW", 100.00, 1_000, 0, 0, 0, 0, false));
        AdaptiveSor sor = new AdaptiveSor(new VenueScorecard(2));
        assertEquals("LOW", sor.route(Side.SELL, 500, venues).lit().getFirst().venue());
    }

    @Test
    void darkProbesShareOneBudget() {
        // Two unknown pools, default probe 5,000 each; budget 50 % of 6,000 = 3,000 in total.
        List<VenueQuote> venues = List.of(
                new VenueQuote("D1", 99.99, 0, 100.01, 0, 0, 0, true),
                new VenueQuote("D2", 99.99, 0, 100.01, 0, 0, 0, true),
                new VenueQuote("D3", 99.99, 0, 100.01, 0, 0, 0, true));
        var d = new AdaptiveSor(new VenueScorecard(1)).route(Side.BUY, 6_000, venues);
        assertEquals(1, d.probes().size(), "the first pool takes the whole budget");
        assertEquals(3_000, d.probes().getFirst().quantity());

        AdaptiveSor small = new AdaptiveSor(new VenueScorecard(1),
                new AdaptiveSor.Config(2.0, 1.0, 0.5, 1_000, 0.5));
        var d2 = small.route(Side.BUY, 5_000, venues);
        assertEquals(List.of(1_000L, 1_000L, 500L),
                d2.probes().stream().map(l -> l.quantity()).toList());
    }

    @Test
    void jitteredTimesKeepAProportionalGap() {
        long gap = 1_000_000L;
        long[] times = new long[200];
        for (int i = 0; i < times.length; i++) {
            times[i] = (i + 1) * gap;
        }
        for (double f : new double[]{0.4, 0.5}) {
            for (long seed = 0; seed < 20; seed++) {
                long[] out = new AntiGamingJitter(seed, 0, f).jitterTimes(times, 0);
                long minSep = Long.MAX_VALUE;
                for (int i = 1; i < out.length; i++) {
                    minSep = Math.min(minSep, out[i] - out[i - 1]);
                    long back = times[i] - out[i];
                    assertTrue(back <= (long) Math.floor(gap * f), "within its own interval");
                }
                long bound = Math.max(1, (long) (gap * (1 - 2 * f)) - 1);
                assertTrue(minSep >= bound, "f=" + f + " minSep=" + minSep + " bound=" + bound);
                assertTrue(out[0] >= 0);
                assertTrue(out[out.length - 1] <= times[times.length - 1]);
            }
        }
    }
}
