package com.quantfinlib.alpha;

import com.quantfinlib.backtest.FillPolicy;
import com.quantfinlib.risk.FactorRiskModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BacktestAttributionTest {

    static final AlphaRiskModels.Spec SPEC = new AlphaRiskModels.Spec(50, 40, 40, 20,
            FactorRiskModel.Settings.defaults());

    static String[] sectors() {
        return RiskModelBacktestTest.sectors().toArray(new String[0]);
    }

    @Test
    void componentsSumToTheNetReturnEveryBar() {
        AlphaContext ctx = RiskModelBacktestTest.panel(11);
        AlphaBacktester.Config cfg = new AlphaBacktester.Config(100, 5, 1, 2, 1, 1e7, 20, 252)
                .withFinancing(30, 0.02, 0.04);
        AlphaBacktester.Traced tr = AlphaBacktester.runTraced(ctx, Factors.momentum(20, 0), cfg,
                (c, s, i) -> PortfolioConstruction.zScoreWeights(s, 1.0, 0.2));
        BacktestAttribution a = BacktestAttribution.attribute(ctx, tr, sectors(), SPEC);
        assertEquals(tr.steps(), a.steps());
        for (int s = 0; s < a.steps(); s++) {
            double sum = a.factorTotal(s) + a.specific(s) + a.trading(s) + a.cost(s)
                    + a.financing(s);
            assertEquals(a.net(s), sum, 1e-12);
            double names = a.net(s) - a.cost(s) - a.financing(s);
            assertEquals(names, a.longLeg(s) + a.shortLeg(s), 1e-12);
            assertEquals(names, a.sector(s, 0) + a.sector(s, 1), 1e-12);
            assertEquals(tr.cost(s), a.cost(s));
        }
        assertTrue(a.cumulativeCost() < 0);
        assertTrue(a.cumulativeFinancing() != 0);
        assertEquals(List.of("EVEN", "ODD"), a.sectorNames());
        double tot = a.cumulativeSpecific() + a.cumulativeTrading() + a.cumulativeCost()
                + a.cumulativeFinancing();
        for (int k = 0; k < a.factorNames().size(); k++) {
            tot += a.cumulativeFactor(k);
        }
        assertEquals(a.cumulativeNet(), tot, 1e-10);
        assertEquals(a.cumulativeLong() + a.cumulativeShort(),
                a.cumulativeSector(0) + a.cumulativeSector(1), 1e-10);
        assertTrue(a.format().contains("specific"));
    }

    @Test
    void closeFillsLeaveNoTradingTermAndMatchExposureTimesFactorReturn() {
        AlphaContext ctx = RiskModelBacktestTest.panel(12);
        AlphaBacktester.Config cfg = new AlphaBacktester.Config(100, 5, 0, 0, 0, 0, 20, 252)
                .withFillPolicy(FillPolicy.CLOSE);
        AlphaBacktester.Traced tr = AlphaBacktester.runTraced(ctx, Factors.momentum(20, 0), cfg,
                (c, s, i) -> PortfolioConstruction.zScoreWeights(s, 1.0, 0.2));
        BacktestAttribution a = BacktestAttribution.attribute(ctx, tr, sectors(), SPEC);
        // independent check against a model estimated over the same bars
        AlphaRiskModels.Spec s = new AlphaRiskModels.Spec(tr.steps(), 40, 40, 20,
                FactorRiskModel.Settings.defaults());
        FactorRiskModel m = AlphaRiskModels.estimate(ctx, tr.startIndex() + tr.steps(),
                sectors(), s);
        for (int st = 0; st < a.steps(); st++) {
            assertEquals(0, a.trading(st), 1e-12, "step " + st);
            double mkt = 0;
            for (int i = 0; i < ctx.symbolCount(); i++) {
                mkt += tr.held(st, i) * m.factorReturn(st, 0);
            }
            assertEquals(mkt, a.factor(st, 0), 1e-14);
        }
    }

    @Test
    void longOnlyBookIsMostlyMarket() {
        AlphaContext ctx = RiskModelBacktestTest.panel(13);
        AlphaBacktester.Config cfg = new AlphaBacktester.Config(100, 5, 0, 0, 0, 0, 20, 252)
                .withFillPolicy(FillPolicy.CLOSE);
        int n = ctx.symbolCount();
        AlphaBacktester.Traced tr = AlphaBacktester.runTraced(ctx, Factors.momentum(20, 0), cfg,
                (c, s, i) -> {
                    double[] w = new double[n];
                    java.util.Arrays.fill(w, 1.0 / n);
                    return w;
                });
        BacktestAttribution a = BacktestAttribution.attribute(ctx, tr, sectors(), SPEC);
        double mk = 0;
        double net = 0;
        for (int s = 0; s < a.steps(); s++) {
            mk += a.factor(s, 0) * a.factor(s, 0);
            net += a.net(s) * a.net(s);
        }
        // the market factor explains most of the variance of an equal-weight long book
        assertTrue(mk / net > 0.7, "market share " + mk / net);
        assertEquals(0, a.cumulativeShort(), 0);
        assertThrows(IllegalArgumentException.class, () -> BacktestAttribution.attribute(ctx, tr,
                new String[2], SPEC));
    }
}
