package com.quantfinlib.alpha;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlphaReportTablesTest {

    @Test
    void icTableMatchesThePerFactorStatistic() {
        AlphaContext ctx = RiskModelBacktestTest.panel(41);
        List<AlphaFactor> fs = List.of(Factors.momentum(20, 0), Factors.lowVolatility(20));
        int[] h = {1, 5};
        double[][] t = AlphaReport.icTable(ctx, fs, 30, h);
        for (int k = 0; k < 2; k++) {
            for (int j = 0; j < 2; j++) {
                assertEquals(AlphaValidation.meanIc(ctx, fs.get(k), 30, ctx.bars(), h[j]),
                        t[k][j], 0);
            }
        }
        String text = AlphaReport.formatIcTable(ctx, fs, 30, h);
        assertTrue(text.contains(String.format(java.util.Locale.ROOT, "%8.4f", t[0][1])));
        assertThrows(IllegalArgumentException.class,
                () -> AlphaReport.icTable(ctx, List.of(), 30, h));
        assertThrows(IllegalArgumentException.class,
                () -> AlphaReport.icTable(ctx, fs, 30, new int[]{0}));
    }

    @Test
    void costBreakdownAndExposureSummary() {
        AlphaContext ctx = RiskModelBacktestTest.panel(42);
        AlphaBacktester.Config cfg = new AlphaBacktester.Config(40, 5, 1, 2, 1, 1e7, 20, 252)
                .withFinancing(30, 0.02, 0.04);
        int n = ctx.symbolCount();
        // half the names long 4% each, half short 2% each: gross 0.72, net 0.24
        AlphaBacktester.Traced tr = AlphaBacktester.runTraced(ctx, Factors.momentum(20, 0), cfg,
                (c, s, i) -> {
                    double[] w = new double[n];
                    for (int j = 0; j < n; j++) {
                        w[j] = j % 2 == 0 ? 0.04 : -0.02;
                    }
                    return w;
                });
        double[] e = AlphaReport.exposureSummary(tr);
        // weights drift between rebalances, so the means sit near the targets
        assertEquals(0.72, e[0], 0.03);
        assertEquals(0.24, e[1], 0.03);
        assertEquals(e[1], e[2] + e[3], 1e-12);
        assertEquals(e[0], e[2] - e[3], 1e-12);
        assertTrue(e[4] <= n && e[4] > n - 1.5);
        String costs = AlphaReport.costBreakdown(tr.result());
        assertTrue(costs.contains("commission") && costs.contains("impact"));
        assertTrue(costs.contains(String.format(java.util.Locale.ROOT, "%10.6f",
                tr.result().totalCostDrag())));
    }
}
