package com.quantfinlib.regulatory;

import com.quantfinlib.orderbook.Side;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The edge branches the hand-calculation tests never reach: zero trades,
 * zero mid, empty fixing windows, zero market volume, flow-less
 * participants. Each is one guard in main code with zero prior coverage.
 */
class RegulatoryEdgeCasesTest {

    @Test
    void orderToTradeRatioIsInfiniteWithNoTrades() {
        assertEquals(Double.POSITIVE_INFINITY, MarketQualityMetrics.orderToTradeRatio(100, 0),
                "all messages, no executions: the ratio regulators flag hardest");
        assertEquals(2.0, MarketQualityMetrics.orderToTradeRatio(100, 50), 1e-12);
    }

    @Test
    void quotedSpreadIsNanOnZeroMidNotInfinite() {
        assertTrue(Double.isNaN(MarketQualityMetrics.quotedSpreadBps(-1, 1)));
        assertEquals(2.0, MarketQualityMetrics.quotedSpreadBps(99.99, 100.01), 1e-9);
    }

    @Test
    void effectiveSpreadSignFollowsTaker() {
        // Buy above mid costs the buyer: positive. Sell above mid earns: negative.
        assertEquals(2.0, MarketQualityMetrics.effectiveSpreadBps(Side.BUY, 100.01, 100), 1e-9);
        assertEquals(-2.0, MarketQualityMetrics.effectiveSpreadBps(Side.SELL, 100.01, 100), 1e-9);
    }

    @Test
    void emptyFixingWindowIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> FixAnalyzer.calculateFix(new double[0]));
    }

    @Test
    void zeroMarketVolumeMeansZeroShareAndNoFlag() {
        FixAnalyzer.FixImpactReport r = FixAnalyzer.analyze(
                new double[]{100}, 99, 100, 10, 0, 0, 0.25);
        assertEquals(0.0, r.participationShare(), 0.0);
        assertFalse(r.flagged());
    }

    @Test
    void flowlessParticipantIsNeverFlaggedEvenAtFullShare() {
        // Buys == sells: net flow 0, no direction to bang the close toward —
        // 100% participation alone must not flag.
        FixAnalyzer.FixImpactReport r = FixAnalyzer.analyze(
                new double[]{101}, 100, 100.5, 50, 50, 100, 0.25);
        assertEquals(1.0, r.participationShare(), 1e-12);
        assertEquals(0, r.netFlow());
        assertFalse(r.flagged());
    }

    @Test
    void classicBangingTheCloseSignatureIsFlagged() {
        // Big buyer (share 0.6), price runs up into the fix and reverts after.
        FixAnalyzer.FixImpactReport r = FixAnalyzer.analyze(
                new double[]{100.4, 100.5, 100.6}, 100.0, 100.1, 60, 0, 100, 0.25);
        assertEquals(100.5, r.fixRate(), 1e-12); // median of the window
        assertTrue(r.runUpBps() > 0);
        assertTrue(r.reversionBps() < 0);
        assertTrue(r.flagged());
    }
}
