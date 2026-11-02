package com.quantfinlib.risk;

/**
 * Pluggable risk metric over a periodic return series. Implement this to add
 * user-defined metrics to the {@link RiskMetricRegistry}.
 */
@FunctionalInterface
public interface RiskMetric {

    double calculate(double[] returns);
}
