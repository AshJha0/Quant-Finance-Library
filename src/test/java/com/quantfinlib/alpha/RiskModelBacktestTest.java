package com.quantfinlib.alpha;

import com.quantfinlib.core.BarSeries;
import com.quantfinlib.risk.FactorRiskModel;
import com.quantfinlib.util.MathUtils;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The factor risk model inside the alpha backtester: model construction
 * from a panel, the optimiser as the risk-attached construction (checked
 * against the closed-form mean-variance solution), the automatic %ADV
 * trade limit, and the per-bar trace that attribution relies on.
 */
class RiskModelBacktestTest {

    static final int NAMES = 24;
    static final int BARS = 420;

    /** Noisy panel with a market factor, two sectors and a persistent per-name drift. */
    static AlphaContext panel(long seed) {
        SplittableRandom rnd = new SplittableRandom(seed);
        Map<String, BarSeries> data = new LinkedHashMap<>();
        double[] beta = new double[NAMES];
        double[] drift = new double[NAMES];
        double[] vol = new double[NAMES];
        for (int i = 0; i < NAMES; i++) {
            beta[i] = 0.6 + 0.8 * rnd.nextDouble();
            drift[i] = 0.0006 * (rnd.nextDouble() - 0.5);
            vol[i] = 0.01 + 0.015 * rnd.nextDouble();
        }
        double[][] close = new double[NAMES][BARS];
        double[][] open = new double[NAMES][BARS];
        double[] px = new double[NAMES];
        java.util.Arrays.fill(px, 50);
        for (int t = 0; t < BARS; t++) {
            double mkt = 0.01 * AlphaStatisticsTest.gaussian(rnd);
            double sec0 = 0.006 * AlphaStatisticsTest.gaussian(rnd);
            for (int i = 0; i < NAMES; i++) {
                double r = drift[i] + beta[i] * mkt + (i % 2 == 0 ? sec0 : -sec0)
                        + vol[i] * AlphaStatisticsTest.gaussian(rnd);
                open[i][t] = px[i] * (1 + 0.2 * r);
                px[i] *= 1 + r;
                close[i][t] = px[i];
            }
        }
        for (int i = 0; i < NAMES; i++) {
            BarSeries.Builder b = BarSeries.builder("N" + (i < 10 ? "0" : "") + i);
            for (int t = 0; t < BARS; t++) {
                double o = open[i][t];
                double c = close[i][t];
                b.add(t * 86_400_000L, o, Math.max(o, c), Math.min(o, c), c,
                        200_000 + 20_000 * i);
            }
            data.put("N" + (i < 10 ? "0" : "") + i, b.build());
        }
        return AlphaContext.of(data);
    }

    static List<String> sectors() {
        List<String> s = new ArrayList<>();
        for (int i = 0; i < NAMES; i++) {
            s.add(i % 2 == 0 ? "EVEN" : "ODD");
        }
        return s;
    }

    static final AlphaRiskModels.Spec SPEC = new AlphaRiskModels.Spec(100, 40, 40, 20,
            FactorRiskModel.Settings.defaults());

    @Test
    void modelFromPanelHasTheStyleFactorsAndCoversLiveNames() {
        AlphaContext ctx = panel(1);
        String[] sec = sectors().toArray(new String[0]);
        FactorRiskModel m = AlphaRiskModels.estimate(ctx, 200, sec, SPEC);
        assertEquals(List.of("MARKET", "EVEN", "ODD", "SIZE", "MOMENTUM", "LOW_VOL"),
                m.factorNames());
        assertEquals(100, m.periods());
        for (int i = 0; i < NAMES; i++) {
            assertTrue(m.covers(i));
            // per-bar total vol of the names lies near the simulated 1.2%-2.8%
            double v = Math.sqrt(m.covariance()[i][i]);
            assertTrue(v > 0.008 && v < 0.04, "vol " + v);
        }
        assertThrows(IllegalArgumentException.class,
                () -> AlphaRiskModels.estimate(ctx, SPEC.firstIndex() - 1, sec, SPEC));
        assertThrows(IllegalArgumentException.class,
                () -> AlphaRiskModels.estimate(ctx, 200, new String[3], SPEC));
    }

    @Test
    void optimiserMatchesClosedFormMeanVarianceWithoutCostsOrLimits() {
        AlphaContext ctx = panel(2);
        AlphaBacktester.Config cfg = new AlphaBacktester.Config(150, 5, 0, 0, 0, 0, 20, 252);
        AlphaBacktester.RiskOptions opt = AlphaBacktester.RiskOptions.defaults()
                .withSpec(SPEC).withSectors(sectors()).withAdvTradeFraction(0)
                .withRiskAversion(50);
        opt = new AlphaBacktester.RiskOptions(opt.spec(), opt.sectors(), 50, 0.05, 10.0, 0, true);
        int idx = 200;
        AlphaFactor f = Factors.momentum(20, 0);
        double[] scores = f.scores(ctx, idx);
        double[] w = AlphaBacktester.riskBuilder(ctx, cfg, opt)
                .weights(ctx, scores, idx, new double[NAMES], 1);
        // closed form: maximise a'w - λ w'(H V)w s.t. 1'w = 0
        // w = (1/(2λH)) V^{-1}(a - ν 1), ν = (1'V^{-1}a)/(1'V^{-1}1)
        FactorRiskModel m = AlphaRiskModels.estimate(ctx, idx,
                sectors().toArray(new String[0]), SPEC);
        double[][] v = m.covariance();
        double[] z = Factors.zScore(scores);
        double[] a = new double[NAMES];
        for (int i = 0; i < NAMES; i++) {
            a[i] = 0.05 * Math.sqrt(v[i][i]) * Math.sqrt(5) * z[i];
        }
        double[][] inv = MathUtils.inverse(v);
        double[] via = MathUtils.matVec(inv, a);
        double[] vi1 = MathUtils.matVec(inv, AlphaBacktester.ones(NAMES));
        double nu = sum(via) / sum(vi1);
        double gross = 0;
        for (int i = 0; i < NAMES; i++) {
            double expected = (via[i] - nu * vi1[i]) / (2 * 50 * 5);
            assertEquals(expected, w[i], 1e-6 * Math.abs(expected) + 1e-7, "name " + i);
            gross += Math.abs(w[i]);
        }
        assertTrue(gross > 0.05);
        assertEquals(0, sum(w), 1e-8);
    }

    @Test
    void costsAndAdvLimitCutTurnover() {
        AlphaContext ctx = panel(3);
        AlphaFactor f = Factors.momentum(20, 0);
        AlphaBacktester.RiskOptions opt = AlphaBacktester.RiskOptions.defaults().withSpec(SPEC)
                .withSectors(sectors()).withAdvTradeFraction(0);
        AlphaBacktester.Config free = new AlphaBacktester.Config(150, 5, 0, 0, 0, 0, 20, 252);
        AlphaBacktester.Config costly = new AlphaBacktester.Config(150, 5, 10, 10, 5, 0, 20, 252);
        double t0 = AlphaBacktester.run(ctx, f, free, opt).meanTurnover();
        double t1 = AlphaBacktester.run(ctx, f, costly, opt).meanTurnover();
        assertTrue(t1 < 0.7 * t0, "free " + t0 + " costly " + t1);

        // %ADV limit: with capital the per-rebalance trade in each name is bounded
        AlphaBacktester.Config big = new AlphaBacktester.Config(150, 5, 0, 0, 0, 5e7, 20, 252)
                .withFillPolicy(com.quantfinlib.backtest.FillPolicy.CLOSE);
        double frac = 0.01;
        AlphaBacktester.Traced tr = AlphaBacktester.runTraced(ctx, f, big,
                opt.withAdvTradeFraction(frac));
        AlphaBacktester.Traced un = AlphaBacktester.runTraced(ctx, f, big, opt);
        assertTrue(tr.result().meanTurnover() < un.result().meanTurnover());
        for (int s = 5; s < tr.steps(); s += 5) {
            int bar = tr.startIndex() + s;
            for (int i = 0; i < NAMES; i++) {
                // held(s) is post-fill at close(bar); held(s-1) drifted to close(bar) is the
                // pre-trade book; the drift over one bar is small, so compare to a 3% band.
                double adv = com.quantfinlib.microstructure.MarketImpactModel
                        .estimate(ctx.series(i), bar, 20).adv();
                double eq = tr.result().netEquity(s);
                double lim = frac * adv * ctx.series(i).close(bar) / (5e7 * eq);
                double traded = Math.abs(tr.held(s, i) - tr.held(s - 1, i));
                assertTrue(traded <= lim * 1.03 + 0.006, "name " + i + " step " + s
                        + " traded " + traded + " limit " + lim);
            }
        }
    }

    @Test
    void traceDecomposesTheNetReturnExactly() {
        AlphaContext ctx = panel(4);
        AlphaBacktester.Config cfg = new AlphaBacktester.Config(150, 7, 1, 2, 1, 1e7, 20, 252)
                .withFinancing(50, 0.02, 0.05);
        AlphaBacktester.Traced tr = AlphaBacktester.runTraced(ctx, Factors.momentum(20, 0), cfg,
                (c, s, i) -> PortfolioConstruction.zScoreWeights(s, 1.0, 0.2));
        assertEquals(tr.result().netEquityLength() - 1, tr.steps());
        for (int s = 0; s < tr.steps(); s++) {
            double sum = tr.cost(s) + tr.financing(s);
            for (double x : tr.namePnlCopy(s)) {
                sum += x;
            }
            assertEquals(tr.netReturn(s), sum, 1e-12);
            assertTrue(tr.cost(s) <= 0);
        }
        // the traced run equals the plain run
        AlphaBacktester.Result plain = AlphaBacktester.run(ctx, Factors.momentum(20, 0), cfg,
                (c, s, i) -> PortfolioConstruction.zScoreWeights(s, 1.0, 0.2));
        assertEquals(plain, tr.result());
        assertEquals(tr.heldCopy(3)[2], tr.held(3, 2));
        assertEquals(tr.namePnlCopy(3)[2], tr.namePnl(3, 2));
    }

    @Test
    void riskOptionsValidation() {
        assertThrows(IllegalArgumentException.class, () -> new AlphaBacktester.RiskOptions(
                SPEC, null, 0, 0.05, 0.1, 0, true));
        AlphaContext ctx = panel(5);
        AlphaBacktester.Config early = new AlphaBacktester.Config(30, 5, 0, 0, 0, 0, 20, 252);
        assertThrows(IllegalArgumentException.class, () -> AlphaBacktester.run(ctx,
                Factors.momentum(20, 0), early, AlphaBacktester.RiskOptions.defaults()));
        assertThrows(IllegalArgumentException.class,
                () -> new AlphaRiskModels.Spec(1, 1, 2, 1, FactorRiskModel.Settings.defaults()));
    }

    static double sum(double[] x) {
        double s = 0;
        for (double v : x) {
            s += v;
        }
        return s;
    }
}
