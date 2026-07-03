package com.fdequant.risk;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom Risk Metrics Framework: registry of built-in and user-defined risk
 * metrics, evaluated together over a return series.
 */
public final class RiskMetricRegistry {

    private final Map<String, RiskMetric> metrics = new ConcurrentHashMap<>();

    /** Creates a registry pre-populated with the built-in metric set. */
    public static RiskMetricRegistry withDefaults() {
        RiskMetricRegistry r = new RiskMetricRegistry();
        r.register("VaR(95%)", returns -> RiskMetrics.historicalVar(returns, 0.95));
        r.register("VaR(99%)", returns -> RiskMetrics.historicalVar(returns, 0.99));
        r.register("CVaR(95%)", returns -> RiskMetrics.conditionalVar(returns, 0.95));
        r.register("ExpectedShortfall(97.5%)", returns -> RiskMetrics.expectedShortfall(returns, 0.975));
        r.register("Volatility(annualized)",
                returns -> RiskMetrics.annualizedVolatility(returns, RiskMetrics.TRADING_DAYS_PER_YEAR));
        r.register("DownsideDeviation", returns -> RiskMetrics.downsideDeviation(returns, 0));
        return r;
    }

    public RiskMetricRegistry register(String name, RiskMetric metric) {
        metrics.put(name, metric);
        return this;
    }

    public RiskMetric get(String name) {
        return metrics.get(name);
    }

    public boolean contains(String name) {
        return metrics.containsKey(name);
    }

    /** Evaluates every registered metric against the given returns. */
    public Map<String, Double> calculateAll(double[] returns) {
        Map<String, Double> out = new LinkedHashMap<>();
        metrics.forEach((name, metric) -> out.put(name, metric.calculate(returns)));
        return out;
    }
}
