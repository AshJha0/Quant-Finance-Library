package com.quantfinlib.risk.simm;

import java.util.Map;

/**
 * The output of {@link SimmCalculator}: the total, and the breakdown by
 * product class and by risk class.
 *
 * @param total          SIMM total, the plain sum of the product-class margins
 * @param productClasses margin per product class present in the input
 * @param parameterLabel label of the calibration used
 */
public record SimmResult(double total, Map<SimmProductClass, ProductClassMargin> productClasses,
                         String parameterLabel) {

    /** Copies the map defensively (iteration order unspecified). */
    public SimmResult {
        productClasses = Map.copyOf(productClasses);
    }

    /**
     * Margin of one product class.
     *
     * @param productClass the product class
     * @param margin       {@code sqrt(sum_r IM_r^2 + sum_{r != s} psi_rs IM_r IM_s)}
     * @param riskClasses  margin per risk class present
     */
    public record ProductClassMargin(SimmProductClass productClass, double margin,
                                     Map<SimmRiskClass, RiskClassMargin> riskClasses) {
        /** Copies the map defensively (iteration order unspecified). */
        public ProductClassMargin {
            riskClasses = Map.copyOf(riskClasses);
        }
    }

    /**
     * Margin of one risk class inside one product class.
     *
     * @param riskClass       the risk class
     * @param delta           delta margin
     * @param vega            vega margin
     * @param curvature       curvature margin
     * @param baseCorrelation base-correlation margin (Credit Qualifying only)
     */
    public record RiskClassMargin(SimmRiskClass riskClass, double delta, double vega,
                                  double curvature, double baseCorrelation) {
        /** @return {@code delta + vega + curvature + baseCorrelation} */
        public double total() {
            return delta + vega + curvature + baseCorrelation;
        }
    }
}
