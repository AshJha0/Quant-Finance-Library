package com.quantfinlib.regulatory;

import com.quantfinlib.orderbook.Side;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegulatoryTest {

    // ---- Market quality indices -----------------------------------------

    @Test
    void spreadMetricsMatchHandCalculations() {
        assertEquals(2.0, MarketQualityMetrics.quotedSpreadBps(99.99, 100.01), 1e-9);
        // Buy at 100.05 vs mid 100: effective spread = 2*0.05/100 = 10bps.
        assertEquals(10.0, MarketQualityMetrics.effectiveSpreadBps(Side.BUY, 100.05, 100.00), 1e-9);
        // Mid later at 100.04: realized = 2*(100.05-100.04)/100.04 ≈ 2bps.
        assertEquals(2.0, MarketQualityMetrics.realizedSpreadBps(Side.BUY, 100.05, 100.04), 0.01);
        // Impact = 2*(100.04-100)/100 = 8bps; effective ≈ realized + impact.
        assertEquals(8.0, MarketQualityMetrics.priceImpactBps(Side.BUY, 100.00, 100.04), 1e-9);
        assertEquals(5.0, MarketQualityMetrics.orderToTradeRatio(500, 100), 1e-12);
        // Sells mirror: selling below mid is also a positive effective spread.
        assertEquals(10.0, MarketQualityMetrics.effectiveSpreadBps(Side.SELL, 99.95, 100.00), 1e-9);
    }

    // ---- MiFID II best execution ------------------------------------------

    @Test
    void bestExecutionReportAggregates() {
        BestExecutionAnalyzer analyzer = new BestExecutionAnalyzer()
                .add(new BestExecutionAnalyzer.OrderOutcome("o1", "LIT_A", Side.BUY, 100,
                        100.00, 100.01, 2_000_000, true))     // +1bp slippage, 2ms
                .add(new BestExecutionAnalyzer.OrderOutcome("o2", "LIT_A", Side.BUY, 100,
                        100.00, 99.99, 4_000_000, true))      // -1bp (price improvement), 4ms
                .add(new BestExecutionAnalyzer.OrderOutcome("o3", "LIT_B", Side.SELL, 100,
                        100.00, 99.97, 6_000_000, true))      // +3bp slippage, 6ms
                .add(new BestExecutionAnalyzer.OrderOutcome("o4", "LIT_B", Side.BUY, 100,
                        100.00, 0, 0, false));                // unfilled

        BestExecutionAnalyzer.BestExecutionReport r = analyzer.report();
        assertEquals(4, r.totalOrders());
        assertEquals(0.75, r.fillRate(), 1e-12);
        assertEquals(1.0, r.avgSlippageBps(), 1e-6);           // (1 - 1 + 3) / 3
        assertEquals(4.0, r.medianLatencyToFillMillis(), 1e-9);
        assertEquals(1.0 / 3, r.atOrBetterThanArrivalPct(), 1e-9);
        assertEquals(0.0, r.avgSlippageBpsByVenue().get("LIT_A"), 1e-6);
        assertEquals(3.0, r.avgSlippageBpsByVenue().get("LIT_B"), 1e-6);
    }

    // ---- WM/R fix analysis ----------------------------------------------------

    @Test
    void fixIsMedianOfWindowSamples() {
        assertEquals(1.0850, FixAnalyzer.calculateFix(new double[]{1.0848, 1.0850, 1.0855}), 1e-12);
        assertEquals(1.08515, FixAnalyzer.calculateFix(new double[]{1.0850, 1.0853}), 1e-12);
    }

    @Test
    void bangingTheCloseSignatureIsFlagged() {
        // Price runs up into the fix while we buy 40% of the window, then reverts.
        double[] window = {1.0850, 1.0854, 1.0858, 1.0862, 1.0866};
        FixAnalyzer.FixImpactReport r = FixAnalyzer.analyze(
                window, 1.0846, 1.0850, 800_000, 0, 2_000_000, 0.25);

        assertEquals(1.0858, r.fixRate(), 1e-12);
        assertTrue(r.runUpBps() > 0);
        assertTrue(r.reversionBps() < 0);
        assertEquals(0.4, r.participationShare(), 1e-12);
        assertTrue(r.flagged());
    }

    @Test
    void benignFixActivityIsNotFlagged() {
        double[] window = {1.0850, 1.0854, 1.0858, 1.0862, 1.0866};
        // Small participation: same pattern, no flag.
        assertFalse(FixAnalyzer.analyze(window, 1.0846, 1.0850,
                50_000, 0, 2_000_000, 0.25).flagged());
        // Large participation but price keeps trending (no reversion): no flag.
        assertFalse(FixAnalyzer.analyze(window, 1.0846, 1.0875,
                800_000, 0, 2_000_000, 0.25).flagged());
        // Large participation but our flow opposes the run-up: no flag.
        assertFalse(FixAnalyzer.analyze(window, 1.0846, 1.0850,
                0, 800_000, 2_000_000, 0.25).flagged());
    }
}
