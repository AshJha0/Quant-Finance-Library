package com.quantfinlib.regulatory;

import com.quantfinlib.core.Side;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FixAndShortfallTest {

    @Test
    void wmrStyleFixIsTheMidOfSeparateMedians() {
        double[] bids = {1.0998, 1.1001, 1.0999, 1.1000, 1.0990};
        double[] offers = {1.1002, 1.1003, 1.1010, 1.1001, 1.1004};
        // median(bids) = 1.0999, median(offers) = 1.1003.
        assertEquals(1.1001, FixAnalyzer.calculateFix(bids, offers), 1e-12);
    }

    @Test
    void peroldDecompositionAddsUpToPaperMinusReal() {
        // Buy 1000 decided at 100, arrival 100.5; filled 600 at 101 and 100 at
        // 102; 300 unfilled, close 103.
        BestExecutionAnalyzer.ShortfallDecomposition d =
                BestExecutionAnalyzer.implementationShortfall(Side.BUY, 100, 100.5,
                        new double[]{600, 100}, new double[]{101, 102}, 300, 103);
        assertEquals(0.5 * 700, d.delayCost(), 1e-9);
        assertEquals(600 * 0.5 + 100 * 1.5, d.executionCost(), 1e-9);
        assertEquals(3 * 300, d.opportunityCost(), 1e-9);
        // Paper P&L at close: 1000·(103 − 100) = 3000. Real: 700·103 − (600·101 + 100·102) = 1300.
        assertEquals(3000 - 1300, d.total(), 1e-9);
    }
}
