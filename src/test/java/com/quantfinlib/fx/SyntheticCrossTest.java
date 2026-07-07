package com.quantfinlib.fx;

import com.quantfinlib.fx.CrossRateEngine.Op;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyntheticCrossTest {

    // EURUSD 1.0850/52, USDJPY 161.50/52 -> EURJPY via MULTIPLY.
    private static final double EU_BID = 1.0850;
    private static final double EU_ASK = 1.0852;
    private static final double UJ_BID = 161.50;
    private static final double UJ_ASK = 161.52;

    @Test
    void multiplyCrossesBothSpreads() {
        double synAsk = SyntheticCross.syntheticAsk(Op.MULTIPLY, EU_BID, EU_ASK, UJ_BID, UJ_ASK);
        double synBid = SyntheticCross.syntheticBid(Op.MULTIPLY, EU_BID, EU_ASK, UJ_BID, UJ_ASK);
        assertEquals(EU_ASK * UJ_ASK, synAsk, 1e-9);
        assertEquals(EU_BID * UJ_BID, synBid, 1e-9);
        assertTrue(synAsk > synBid, "the synthetic must have a positive spread");
    }

    @Test
    void divideUsesOppositeSidesOfTheSharedQuoteLeg() {
        // EURGBP = EURUSD / GBPUSD: buy EUR (ask A), sell GBP (bid B).
        double gbBid = 1.2700;
        double gbAsk = 1.2702;
        double synAsk = SyntheticCross.syntheticAsk(Op.DIVIDE, EU_BID, EU_ASK, gbBid, gbAsk);
        double synBid = SyntheticCross.syntheticBid(Op.DIVIDE, EU_BID, EU_ASK, gbBid, gbAsk);
        assertEquals(EU_ASK / gbBid, synAsk, 1e-9);
        assertEquals(EU_BID / gbAsk, synBid, 1e-9);
        assertTrue(synAsk > synBid);
    }

    @Test
    void routeChoiceFollowsTheCheaperAllIn() {
        double synAsk = EU_ASK * UJ_ASK;                       // 175.281...
        // Direct book wider than the legs: synthetic wins the buy.
        assertTrue(SyntheticCross.buySyntheticWins(synAsk + 0.02, Op.MULTIPLY,
                EU_BID, EU_ASK, UJ_BID, UJ_ASK));
        // Direct book tighter: direct wins.
        assertFalse(SyntheticCross.buySyntheticWins(synAsk - 0.02, Op.MULTIPLY,
                EU_BID, EU_ASK, UJ_BID, UJ_ASK));
        double savings = SyntheticCross.buySavings(synAsk + 0.02, Op.MULTIPLY,
                EU_BID, EU_ASK, UJ_BID, UJ_ASK);
        assertEquals(0.02, savings, 1e-9);
        // Sell mirror.
        double synBid = EU_BID * UJ_BID;
        assertTrue(SyntheticCross.sellSyntheticWins(synBid - 0.02, Op.MULTIPLY,
                EU_BID, EU_ASK, UJ_BID, UJ_ASK));
        assertFalse(SyntheticCross.sellSyntheticWins(synBid + 0.02, Op.MULTIPLY,
                EU_BID, EU_ASK, UJ_BID, UJ_ASK));
    }

    @Test
    void unpricedRoutesNeverWin() {
        assertFalse(SyntheticCross.buySyntheticWins(Double.NaN, Op.MULTIPLY,
                EU_BID, EU_ASK, UJ_BID, UJ_ASK));
        assertFalse(SyntheticCross.buySyntheticWins(175.30, Op.MULTIPLY,
                EU_BID, Double.NaN, UJ_BID, UJ_ASK));
        assertFalse(SyntheticCross.sellSyntheticWins(175.20, Op.MULTIPLY,
                Double.NaN, EU_ASK, UJ_BID, UJ_ASK));
    }

    @Test
    void zeroPricedLegsNeverWinEither() {
        // Zero is the Java default and what an empty FxTierBook tier reads
        // as; a zero DIVIDE denominator would produce +Infinity "savings".
        assertFalse(SyntheticCross.sellSyntheticWins(1.0850, Op.DIVIDE,
                1.10, 1.101, 1.27, 0.0));                    // bidA/askB=0 -> inf
        assertFalse(SyntheticCross.buySyntheticWins(175.30, Op.MULTIPLY,
                EU_BID, EU_ASK, UJ_BID, 0.0));               // askB=0 -> synth 0
        assertFalse(SyntheticCross.buySyntheticWins(0.0, Op.MULTIPLY,
                EU_BID, EU_ASK, UJ_BID, UJ_ASK));            // unquoted direct
        assertTrue(Double.isNaN(SyntheticCross.buySavings(175.30, Op.MULTIPLY,
                EU_BID, -1.0, UJ_BID, UJ_ASK)),
                "negative legs are unpriced, not tradable");
    }

    @Test
    void routeMathIsAllocationFree() {
        double blackhole = 0;
        for (int i = 0; i < 200_000; i++) {                  // warm-up
            blackhole += SyntheticCross.buySavings(175.30 + (i % 5) * 0.01,
                    Op.MULTIPLY, EU_BID, EU_ASK, UJ_BID, UJ_ASK + (i % 3) * 0.01);
        }
        var mx = (com.sun.management.ThreadMXBean)
                java.lang.management.ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long before = mx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < 500_000; i++) {
            blackhole += SyntheticCross.buySavings(175.30 + (i % 5) * 0.01,
                    Op.MULTIPLY, EU_BID, EU_ASK, UJ_BID, UJ_ASK + (i % 3) * 0.01);
        }
        long allocated = mx.getThreadAllocatedBytes(tid) - before;
        assertTrue(allocated < 100_000, "cross math allocated " + allocated + " bytes");
        assertTrue(blackhole != 0);
    }
}
