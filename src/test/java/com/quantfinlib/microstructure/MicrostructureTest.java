package com.quantfinlib.microstructure;

import com.quantfinlib.orderbook.Side;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MicrostructureTest {

    @Test
    void squareRootImpactScalesAsSqrt() {
        MarketImpactModel model = new MarketImpactModel(1_000_000, 0.01);
        double small = model.squareRootImpactBps(10_000);    // 1% of ADV
        double large = model.squareRootImpactBps(40_000);    // 4% of ADV
        assertEquals(2.0, large / small, 1e-9);              // sqrt(4) = 2
        assertEquals(10.0, small, 1e-9);                     // 1 * 0.01 * 0.1 * 1e4
    }

    @Test
    void impactDecompositionAndExpectedCost() {
        MarketImpactModel model = new MarketImpactModel(1_000_000, 0.01)
                .withCoefficients(1.0, 20.0, 0.5);
        assertEquals(10.0, model.temporaryImpactBps(0.5), 1e-9);          // 20 * 0.5
        assertEquals(5.0, model.permanentImpactBps(100_000), 1e-9);       // 0.5*0.01*0.1*1e4
        assertEquals(5.0 / 2 + 10.0, model.expectedCostBps(100_000, 0.5), 1e-9);
    }

    @Test
    void tcaMatchesHandComputedNumbers() {
        List<Execution> fills = List.of(
                new Execution("EURUSD", Side.BUY, 100.02, 50, 1, "LIT_A"),
                new Execution("EURUSD", Side.BUY, 100.02, 50, 2, "LIT_A"));
        TransactionCostAnalyzer.TcaReport r = TransactionCostAnalyzer.analyze(
                fills, 100.00, 100.01, new double[]{100.00, 100.00});

        assertEquals(100, r.totalQuantity());
        assertEquals(100.02, r.avgExecutionPrice(), 1e-9);
        assertEquals(2.0, r.implementationShortfallBps(), 1e-6);   // (100.02-100)/100
        assertEquals(1.0, r.slippageVsVwapBps(), 1e-4);            // vs 100.01
        assertEquals(4.0, r.avgEffectiveSpreadBps(), 1e-6);        // 2 * 2bps
    }

    @Test
    void tcaSignsCostForSells() {
        // Selling below the arrival mid is a cost (positive shortfall).
        List<Execution> fills = List.of(
                new Execution("X", Side.SELL, 99.98, 100, 1, "V"));
        TransactionCostAnalyzer.TcaReport r = TransactionCostAnalyzer.analyze(
                fills, 100.00, 100.00, new double[]{100.00});
        assertEquals(2.0, r.implementationShortfallBps(), 1e-6);
        assertEquals(4.0, r.avgEffectiveSpreadBps(), 1e-6);
    }

    @Test
    void queueFillProbabilityBehaviour() {
        // No queue, huge expected volume -> near certain.
        assertTrue(QueueModel.fillProbability(0, 10, 100_000) > 0.99);
        // Deeper queue -> strictly lower probability.
        double shallow = QueueModel.fillProbability(100, 10, 1_000);
        double deep = QueueModel.fillProbability(5_000, 10, 1_000);
        assertTrue(shallow > deep);
        // No expected volume -> zero.
        assertEquals(0, QueueModel.fillProbability(0, 10, 0), 0.0);
    }

    @Test
    void latencyAdvantageIsPositiveAndBounded() {
        // 100ms advantage with others joining at 10k qty/sec -> 1000 qty less ahead.
        assertEquals(1_000.0, QueueModel.queueGrowth(10_000, 100_000_000L), 1e-9);
        double edge = QueueModel.latencyFillAdvantage(500, 100, 2_000, 10_000, 100_000_000L);
        assertTrue(edge > 0 && edge < 1, "edge=" + edge);
        // Zero latency advantage -> zero edge.
        assertEquals(0, QueueModel.latencyFillAdvantage(500, 100, 2_000, 10_000, 0), 1e-12);
    }
}
