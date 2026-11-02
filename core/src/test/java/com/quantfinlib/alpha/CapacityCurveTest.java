package com.quantfinlib.alpha;

import com.quantfinlib.core.BarSeries;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapacityCurveTest {

    /** Ten names with persistent drifts of ±0.1-0.3% a bar, 1% noise, 100k shares a bar at ~$100. */
    static AlphaContext drifting() {
        SplittableRandom rnd = new SplittableRandom(21);
        Map<String, BarSeries> data = new LinkedHashMap<>();
        for (int s = 0; s < 10; s++) {
            double drift = 0.001 * (s - 4.5) / 1.5;
            BarSeries.Builder b = BarSeries.builder("D" + s);
            double close = 100;
            for (int i = 0; i < 300; i++) {
                double open = close;
                close = open * (1 + drift + 0.01 * AlphaStatisticsTest.gaussian(rnd));
                b.add(i * 86_400_000L, open, Math.max(open, close), Math.min(open, close), close,
                        100_000);
            }
            data.put("D" + s, b.build());
        }
        return AlphaContext.of(data);
    }

    static final AlphaBacktester.WeightBuilder BOOK =
            (c, s, i) -> PortfolioConstruction.zScoreWeights(s, 1.0, 0.3);

    @Test
    void impactFollowsTheSquareRootLawAndNetSharpeFalls() {
        AlphaContext ctx = drifting();
        AlphaBacktester.Config base = new AlphaBacktester.Config(40, 5, 1, 1, 0, 1, 20, 252);
        double[] caps = {1e5, 4e5, 1e7, 1e8, 4e8};
        CapacityCurve c = CapacityCurve.compute(ctx, Factors.momentum(20, 0), base, BOOK, caps, 0);
        // square-root impact: per-unit cost ∝ √capital, so drag(4C)/drag(C) = 2 while the
        // equity path is set by the gross returns (impact negligible at these sizes)
        assertEquals(2, c.impactDrag(1) / c.impactDrag(0), 0.02);
        for (int i = 1; i < c.size(); i++) {
            assertTrue(c.impactDrag(i) > c.impactDrag(i - 1));
            assertTrue(c.netSharpe(i) < c.netSharpe(i - 1));
            // without a participation cap the gross book does not depend on capital
            assertEquals(c.grossSharpe(0), c.grossSharpe(i), 1e-12);
        }
        assertTrue(c.grossSharpe(0) > 1, "gross Sharpe " + c.grossSharpe(0));
        double floor = 0.5 * (c.netSharpe(0) + c.netSharpe(c.size() - 1));
        double cap = c.capacityAt(floor);
        assertTrue(cap > caps[0] && cap < caps[caps.length - 1]);
        assertTrue(Double.isNaN(c.capacityAt(c.netSharpe(0) + 1)));
        assertEquals(caps[caps.length - 1], c.capacityAt(-100));
        assertTrue(c.format().contains("netSharpe"));
        assertEquals(5, c.capitalsCopy().length);
        assertEquals(c.netSharpe(2), c.netSharpeCopy()[2]);
        assertTrue(c.totalCostDrag(0) > c.impactDrag(0));
    }

    @Test
    void participationCapBoundsImpactButOpensATrackingGap() {
        AlphaContext ctx = drifting();
        AlphaBacktester.Config base = new AlphaBacktester.Config(40, 5, 1, 1, 0, 1, 20, 252);
        double[] caps = {1e5, 1e8, 1e9};
        CapacityCurve capped = CapacityCurve.compute(ctx, Factors.momentum(20, 0), base, BOOK,
                caps, 0.05);
        assertEquals(0, capped.trackingGap(0), 0.01);
        assertTrue(capped.trackingGap(2) > capped.trackingGap(1));
        assertTrue(capped.trackingGap(2) > 0.05);
        assertThrows(IllegalArgumentException.class, () -> CapacityCurve.compute(ctx,
                Factors.momentum(20, 0), base, BOOK, new double[]{2, 1}, 0));
        assertThrows(IllegalArgumentException.class, () -> CapacityCurve.compute(ctx,
                Factors.momentum(20, 0), base, BOOK, new double[0], 0));
    }

    @Test
    void riskModelConstructionRunsAcrossCapitals() {
        AlphaContext ctx = RiskModelBacktestTest.panel(31);
        AlphaBacktester.Config base = new AlphaBacktester.Config(150, 10, 1, 1, 0, 1, 20, 252);
        AlphaBacktester.RiskOptions opt = AlphaBacktester.RiskOptions.defaults()
                .withSpec(RiskModelBacktestTest.SPEC);
        CapacityCurve c = CapacityCurve.compute(ctx, Factors.momentum(20, 0), base, opt,
                new double[]{1e6, 1e9}, 0.1);
        // the automatic %ADV limit shrinks the large book's trades, so only finiteness and
        // a positive impact charge are asserted here
        assertTrue(Double.isFinite(c.netSharpe(0)) && Double.isFinite(c.netSharpe(1)));
        assertTrue(c.impactDrag(1) > 0);
        assertTrue(c.toString().startsWith("CapacityCurve"));
    }
}
