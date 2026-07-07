package com.quantfinlib.microstructure;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Round 5: learned impact, jump-robust vol, closing-auction structure. */
class QuantModels5Test {

    // ------------------------------------------------------------------
    // KylesLambda — impact learned from the tape
    // ------------------------------------------------------------------

    @Test
    void recoversThePlantedDepth() {
        // Planted market: every unit of signed flow moves the mid 1e-6.
        KylesLambda kyle = new KylesLambda(0.02);
        Random rnd = new Random(42);
        for (int i = 0; i < 5_000; i++) {
            double q = Math.round(rnd.nextGaussian() * 2_000);
            double move = 1e-6 * q + 1e-4 * rnd.nextGaussian();
            kyle.onSample(move, q);
        }
        assertEquals(1e-6, kyle.lambda(), 3e-7, "planted lambda recovered");
        // Impact of a 10,000-share child on a 100 mid: 1e-6·1e4/100·1e4 = 1 bp.
        assertEquals(1.0, kyle.impactBps(10_000, 100), 0.35,
                "MarketState.impactBps units");
    }

    @Test
    void aNoisyNegativeLambdaIsNeverASubsidy() {
        KylesLambda kyle = new KylesLambda(0.5);
        kyle.onSample(-0.01, 1_000);       // flow up, price down: negative estimate
        assertTrue(kyle.lambda() < 0, "the raw diagnostic stays honest");
        assertEquals(0, kyle.impactBps(10_000, 100), 0.0,
                "the executor never gets paid to trade");
    }

    @Test
    void kyleGapsAndGuards() {
        KylesLambda kyle = new KylesLambda(0.5);
        kyle.onSample(Double.NaN, 1_000);
        kyle.onSample(0.01, Double.NaN);
        kyle.onSample(0.01, 0);            // no flow, no information
        assertEquals(0, kyle.samples());
        assertEquals(0, kyle.lambda(), 0.0);
        kyle.onSample(0.001, 1_000);
        assertEquals(0, kyle.impactBps(10_000, Double.NaN), 0.0, "garbage mid is neutral");
        assertEquals(0, kyle.impactBps(0, 100), 0.0, "zero child costs nothing");
        assertEquals(0, kyle.impactBps(Double.POSITIVE_INFINITY, 100), 0.0,
                "an infinite quantity is a gap, never infinite bps");
        assertEquals(kyle.impactBps(10_000, 100), kyle.impactBps(-10_000, 100), 0.0,
                "a signed sell size costs the same as the buy — impact is not free one way");
        assertThrows(IllegalArgumentException.class, () -> new KylesLambda(0));
    }

    // ------------------------------------------------------------------
    // JumpRobustVolatility — bipower variation
    // ------------------------------------------------------------------

    @Test
    void diffusionAgreesButAJumpSplitsTheEstimators() {
        JumpRobustVolatility vol = new JumpRobustVolatility(10_000_000_000L);
        Random rnd = new Random(7);
        long dt = 1_000_000_000L;          // 1s sampling
        double sigma = 1e-4;
        for (int i = 0; i < 5_000; i++) {
            vol.onReturn(sigma * rnd.nextGaussian(), dt);
        }
        // Pure diffusion: the two estimators agree (that is bipower's point).
        assertEquals(vol.rawVolPerSqrtSecond(), vol.volPerSqrtSecond(),
                0.25 * vol.rawVolPerSqrtSecond(), "diffusion: estimators agree");
        assertTrue(vol.jumpFraction() < 0.25, "no jumps to attribute");

        // One 50-sigma headline print, then one normal return.
        vol.onReturn(50 * sigma, dt);
        vol.onReturn(sigma, dt);
        assertTrue(vol.rawVolPerSqrtSecond() > 2 * vol.volPerSqrtSecond(),
                "the raw estimator read the headline as a regime; bipower did not: raw="
                        + vol.rawVolPerSqrtSecond() + " robust=" + vol.volPerSqrtSecond());
        assertTrue(vol.jumpFraction() > 0.5, "the jump is attributed as a jump");
    }

    @Test
    void irregularSamplingIsNotMisreadAsJumps() {
        // Regression: normalizing the two-return product by the current dt
        // alone made a cadence change look like a volatility change — on an
        // alternating 10s/0.1s clock a pure diffusion read jumpFraction
        // ~0.74. The √(dt·dt₋₁) normalization keeps the estimators agreeing.
        JumpRobustVolatility vol = new JumpRobustVolatility(100_000_000_000L);
        Random rnd = new Random(1);
        double sigma = 1e-4;                                // per √second
        for (int i = 0; i < 20_000; i++) {
            long dt = (i & 1) == 0 ? 10_000_000_000L : 100_000_000L;
            double dtSec = dt * 1e-9;
            vol.onReturn(sigma * Math.sqrt(dtSec) * rnd.nextGaussian(), dt);
        }
        assertEquals(vol.rawVolPerSqrtSecond(), vol.volPerSqrtSecond(),
                0.25 * vol.rawVolPerSqrtSecond(),
                "a bursty clock is not a jump: raw=" + vol.rawVolPerSqrtSecond()
                        + " robust=" + vol.volPerSqrtSecond());
        assertTrue(vol.jumpFraction() < 0.25,
                "no phantom jumps from cadence: " + vol.jumpFraction());
    }

    @Test
    void aGapBreaksThePairingSoNeighborsAreNeverInvented() {
        JumpRobustVolatility vol = new JumpRobustVolatility(10_000_000_000L);
        vol.onReturn(1e-4, 1_000_000_000L);
        vol.onReturn(1e-4, 1_000_000_000L);        // bipower seeds here
        double bipowerBefore = vol.volPerSqrtSecond();
        vol.onReturn(Double.NaN, 1_000_000_000L);  // feed gap
        vol.onReturn(5e-3, 1_000_000_000L);        // big first return after the gap
        assertEquals(bipowerBefore, vol.volPerSqrtSecond(), 0.0,
                "the post-gap return has no neighbor: bipower must not pair across");
        assertTrue(vol.rawVolPerSqrtSecond() > bipowerBefore,
                "the raw estimator still sees it");
        assertThrows(IllegalArgumentException.class, () -> new JumpRobustVolatility(0));
    }

    // ------------------------------------------------------------------
    // ClosingAuctionModel — the documented-contract structure
    // ------------------------------------------------------------------

    @Test
    void learnsTheAuctionShareAcrossDays() {
        ClosingAuctionModel m = new ClosingAuctionModel(0.1, 0.5, 0.3);
        m.onAuctionResult(10_000, 90_000);          // day 1: 10% -> seeds
        assertEquals(0.10, m.auctionShare(), 1e-12);
        m.onAuctionResult(30_000, 70_000);          // day 2: 30% -> EWMA fold
        assertEquals(0.12, m.auctionShare(), 1e-12, "0.10 + 0.1*(0.30-0.10)");
        assertEquals(2, m.daysLearned());
        m.onAuctionResult(0, 0);                    // empty day teaches nothing
        assertEquals(2, m.daysLearned());
    }

    @Test
    void anOppositeImbalanceRaisesTheReserve() {
        ClosingAuctionModel m = new ClosingAuctionModel(0.1, 0.5, 0.3);
        m.onAuctionResult(10_000, 90_000);          // learned share 0.10
        // 50k unmatched SELL interest vs 50k paired: ratio -0.5.
        m.onImbalance(50_000, false, 50_000, 100.02, 100.00);
        assertEquals(0.125, m.reserveFraction(true), 1e-12,
                "a buy parent reserves MORE into sell-side imbalance");
        assertEquals(0.075, m.reserveFraction(false), 1e-12,
                "a sell parent would join the crowd: reserve less");
        assertEquals(-0.5, m.imbalanceRatio(), 1e-12);
        assertEquals(2e-4, m.indicativePressure(), 1e-9);
    }

    @Test
    void reserveIsCappedAndColdStartsAtZero() {
        ClosingAuctionModel cold = new ClosingAuctionModel();
        assertEquals(0, cold.reserveFraction(true), 0.0, "nothing learned, nothing held");

        ClosingAuctionModel hot = new ClosingAuctionModel(0.5, 10, 0.3);
        hot.onAuctionResult(20_000, 80_000);        // share 0.2, huge sensitivity
        hot.onImbalance(90_000, false, 10_000, 100, 100);
        assertEquals(0.3, hot.reserveFraction(true), 1e-12, "hard cap holds");
        assertEquals(0, hot.reserveFraction(false), 1e-12, "floor holds");

        // Garbage disseminations are gaps.
        hot.onImbalance(0, true, 0, 100, 100);
        hot.onImbalance(1_000, true, 0, Double.NaN, 100);
        hot.onImbalance(-5, true, 100, 100, 100);
        assertEquals(-0.9, hot.imbalanceRatio(), 1e-12, "state untouched by garbage");
        assertThrows(IllegalArgumentException.class,
                () -> new ClosingAuctionModel(0, 0.5, 0.3));
    }

    // ------------------------------------------------------------------
    // Allocation
    // ------------------------------------------------------------------

    @Test
    void streamingUpdatesAreAllocationFree() {
        KylesLambda kyle = new KylesLambda(0.02);
        JumpRobustVolatility vol = new JumpRobustVolatility();
        ClosingAuctionModel auction = new ClosingAuctionModel();
        auction.onAuctionResult(10_000, 90_000);
        double blackhole = 0;
        for (int i = 0; i < 200_000; i++) {            // warm-up
            blackhole += step(kyle, vol, auction, i);
        }
        var mx = (com.sun.management.ThreadMXBean)
                java.lang.management.ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long before = mx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < 500_000; i++) {
            blackhole += step(kyle, vol, auction, i);
        }
        long allocated = mx.getThreadAllocatedBytes(tid) - before;
        assertTrue(allocated < 100_000, "round-5 models allocated " + allocated + " bytes");
        assertTrue(blackhole != 0);
    }

    private static double step(KylesLambda kyle, JumpRobustVolatility vol,
                               ClosingAuctionModel auction, int i) {
        double q = (i * 31 % 200) - 100;
        kyle.onSample(1e-6 * q + ((i * 17) % 9 - 4) * 1e-5, q == 0 ? 1 : q);
        vol.onReturn(((i * 13) % 11 - 5) * 2e-5, 1_000_000_000L);
        auction.onImbalance(1_000 + (i % 500), (i & 1) == 0, 5_000, 100.01, 100.00);
        return kyle.impactBps(10_000, 100) + vol.volPerSqrtSecond()
                + vol.jumpFraction() + auction.reserveFraction((i & 2) == 0);
    }
}
