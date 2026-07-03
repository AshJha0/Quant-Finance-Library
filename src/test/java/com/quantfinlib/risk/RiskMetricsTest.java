package com.quantfinlib.risk;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskMetricsTest {

    private static double[] gaussianReturns(int n, double mean, double std, long seed) {
        SplittableRandom rnd = new SplittableRandom(seed);
        double[] r = new double[n];
        for (int i = 0; i < n; i++) {
            r[i] = mean + std * rnd.nextGaussian();
        }
        return r;
    }

    @Test
    void maxDrawdownKnownValue() {
        double[] equity = {100, 120, 90, 130, 130};
        assertEquals(0.25, RiskMetrics.maxDrawdown(equity), 1e-12);
    }

    @Test
    void historicalVarMatchesQuantile() {
        // 100 returns: -0.50, -0.49, ..., +0.49 — 5th percentile sits near -0.4505
        double[] r = new double[100];
        for (int i = 0; i < 100; i++) {
            r[i] = (i - 50) / 100.0;
        }
        double var95 = RiskMetrics.historicalVar(r, 0.95);
        assertTrue(var95 > 0.44 && var95 < 0.46, "var95=" + var95);
    }

    @Test
    void cvarAtLeastVar() {
        double[] r = gaussianReturns(5000, 0.0, 0.02, 42);
        double var = RiskMetrics.historicalVar(r, 0.95);
        double cvar = RiskMetrics.conditionalVar(r, 0.95);
        assertTrue(cvar >= var, "cvar " + cvar + " < var " + var);
    }

    @Test
    void parametricVarCloseToHistoricalForGaussian() {
        double[] r = gaussianReturns(20000, 0.0002, 0.01, 7);
        double hist = RiskMetrics.historicalVar(r, 0.99);
        double param = RiskMetrics.parametricVar(r, 0.99);
        assertEquals(hist, param, 0.002);
    }

    @Test
    void sharpePositiveForPositiveDrift() {
        double[] r = gaussianReturns(2000, 0.001, 0.01, 3);
        assertTrue(RiskMetrics.sharpeRatio(r, 0, 252) > 0);
        assertTrue(RiskMetrics.sortinoRatio(r, 0, 252) > 0);
    }

    @Test
    void betaOfSelfIsOne() {
        double[] r = gaussianReturns(1000, 0.0, 0.015, 5);
        assertEquals(1.0, RiskMetrics.beta(r, r), 1e-9);
        assertEquals(1.0, RiskMetrics.correlation(r, r), 1e-9);
    }

    @Test
    void portfolioAnalyzerDecompositionSumsToOne() {
        double[][] returns = {
                gaussianReturns(500, 0.0005, 0.01, 1),
                gaussianReturns(500, 0.0003, 0.02, 2),
                gaussianReturns(500, 0.0004, 0.015, 3)
        };
        PortfolioRiskAnalyzer analyzer = new PortfolioRiskAnalyzer(
                new String[]{"A", "B", "C"}, returns, new double[]{0.5, 0.3, 0.2});
        PortfolioRiskAnalyzer.RiskReport report = analyzer.analyze(0.95, 252);

        double sum = report.riskContributions().values().stream().mapToDouble(Double::doubleValue).sum();
        assertEquals(1.0, sum, 1e-9);
        assertTrue(report.annualizedVolatility() > 0);
        assertTrue(report.valueAtRisk() > 0);
        assertTrue(report.conditionalValueAtRisk() >= report.valueAtRisk());
        assertEquals(1.0, report.correlationMatrix()[0][0], 1e-12);
    }

    @Test
    void registryEvaluatesBuiltInsAndCustomMetrics() {
        double[] r = gaussianReturns(500, 0.0, 0.01, 9);
        RiskMetricRegistry registry = RiskMetricRegistry.withDefaults()
                .register("WorstDay", returns -> {
                    double worst = 0;
                    for (double x : returns) {
                        worst = Math.min(worst, x);
                    }
                    return -worst;
                });
        Map<String, Double> all = registry.calculateAll(r);
        assertTrue(all.containsKey("VaR(95%)"));
        assertTrue(all.containsKey("WorstDay"));
        assertTrue(all.get("WorstDay") > 0);
    }
}
