package com.quantfinlib.pricing;

import com.quantfinlib.pricing.TriangularArbitrage.Quote;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PricingTest {

    @Test
    void consistentTriangleShowsNoArbitrage() {
        Quote eurusd = new Quote(1.1000, 1.1002);
        Quote usdjpy = new Quote(150.00, 150.02);
        Quote eurjpy = new Quote(165.00, 165.06);   // consistent with 1.1001 * 150.01 = 165.03
        assertFalse(TriangularArbitrage.exists(eurusd, usdjpy, eurjpy, 0.0));
        assertTrue(TriangularArbitrage.arbitrageBps(eurusd, usdjpy, eurjpy) < 0);
    }

    @Test
    void dislocatedCrossShowsExecutableArbitrage() {
        Quote eurusd = new Quote(1.1000, 1.1002);
        Quote usdjpy = new Quote(150.00, 150.02);
        // EURJPY bid far above synthetic ask (1.1002 * 150.02 = 165.05): sell direct, buy synthetic.
        Quote eurjpy = new Quote(165.40, 165.45);
        double bps = TriangularArbitrage.arbitrageBps(eurusd, usdjpy, eurjpy);
        assertTrue(bps > 15, "expected >15bps edge, got " + bps);
        assertTrue(TriangularArbitrage.exists(eurusd, usdjpy, eurjpy, 5));
        assertEquals(1.1001 * 150.01, TriangularArbitrage.impliedCrossMid(eurusd, usdjpy), 1e-9);
    }

    @Test
    void forwardCurveInterpolatesAndExtrapolates() {
        ForwardCurve curve = new ForwardCurve(1.1000)
                .addPoint(0.25, 1.1050)
                .addPoint(1.0, 1.1200);

        assertEquals(1.1000, curve.forward(0), 1e-12);
        assertEquals(1.1050, curve.forward(0.25), 1e-12);
        // Midway between 0.25y and 1.0y pillars.
        double halfway = 1.1050 + (1.1200 - 1.1050) * (0.625 - 0.25) / 0.75;
        assertEquals(halfway, curve.forward(0.625), 1e-12);
        // Extrapolation continues the last slope.
        double slope = (1.1200 - 1.1050) / 0.75;
        assertEquals(1.1200 + slope * 0.5, curve.forward(1.5), 1e-9);
        assertEquals(1.1200 - 1.1000, curve.forwardPoints(1.0), 1e-12);
    }

    @Test
    void coveredInterestParityChecks() {
        double spot = 1.10, rd = 0.05, rf = 0.02, t = 1.0;
        double fair = ForwardCurve.theoreticalForward(spot, rd, rf, t);
        assertEquals(1.10 * 1.05 / 1.02, fair, 1e-12);

        ForwardCurve fairCurve = new ForwardCurve(spot).addPoint(t, fair);
        assertEquals(0, fairCurve.mispricingBps(t, rd, rf), 1e-9);
        // Implied differential recovers ~ln(F/S)/t.
        assertEquals(Math.log(fair / spot), fairCurve.impliedRateDifferential(t), 1e-12);

        ForwardCurve richCurve = new ForwardCurve(spot).addPoint(t, fair * 1.001);
        assertEquals(10, richCurve.mispricingBps(t, rd, rf), 0.05);
    }

    @Test
    void micropriceAndLatencyAdjustedFair() {
        assertEquals(100.8, FairValueEngine.microprice(99, 101, 90, 10), 1e-12);

        FairValueEngine engine = new FairValueEngine(64, 1_000_000_000L);
        // Mid rising by 1.0 per second: quotes 100ms apart, +0.1 each.
        for (int i = 0; i < 10; i++) {
            double mid = 100 + 0.1 * i;
            engine.onQuote(mid - 0.01, mid + 0.01, 500, 500, i * 100_000_000L);
        }
        assertEquals(1.0, engine.driftPerSecond(), 0.01);
        // Balanced book: microprice = last mid; 50ms latency adds ~0.05 of drift.
        assertEquals(100.9, engine.microprice(), 1e-9);
        assertEquals(100.95, engine.latencyAdjustedFair(50_000_000L), 0.005);
    }
}
