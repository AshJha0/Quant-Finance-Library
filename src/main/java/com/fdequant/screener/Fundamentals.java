package com.fdequant.screener;

/**
 * Fundamental data for one instrument. Use {@link Double#NaN} for unknown
 * fields; fundamental filters never match on NaN values.
 */
public record Fundamentals(
        double marketCap,
        double peRatio,
        double pbRatio,
        double eps,
        double roe,
        double dividendYield,
        double debtToEquity) {

    public static Fundamentals unknown() {
        return new Fundamentals(Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                Double.NaN, Double.NaN, Double.NaN);
    }
}
