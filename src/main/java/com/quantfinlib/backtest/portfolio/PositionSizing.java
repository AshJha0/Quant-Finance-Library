package com.quantfinlib.backtest.portfolio;

/**
 * Position sizing rules: Kelly, fixed-fractional risk, inverse-volatility
 * weighting, and volatility targeting — the building blocks for
 * {@link PortfolioStrategy} weight construction.
 */
public final class PositionSizing {

    private PositionSizing() {
    }

    /** Full Kelly fraction for a return stream: {@code f* = μ / σ²}. */
    public static double kellyFraction(double meanReturn, double variance) {
        return variance == 0 ? 0 : meanReturn / variance;
    }

    /** Half-Kelly — the practitioner's standard, trading growth for drawdown. */
    public static double halfKelly(double meanReturn, double variance) {
        return kellyFraction(meanReturn, variance) / 2;
    }

    /**
     * Fixed-fractional sizing: shares such that hitting the stop loses exactly
     * {@code riskFraction} of equity.
     */
    public static double fixedFractionalQuantity(double equity, double riskFraction,
                                                 double entryPrice, double stopPrice) {
        double perShareRisk = Math.abs(entryPrice - stopPrice);
        if (perShareRisk == 0) {
            throw new IllegalArgumentException("entry equals stop: undefined risk");
        }
        return equity * riskFraction / perShareRisk;
    }

    /** Normalized inverse-volatility weights (equal weight for any zero vols). */
    public static double[] inverseVolatilityWeights(double[] vols) {
        double[] inv = new double[vols.length];
        double sum = 0;
        for (int i = 0; i < vols.length; i++) {
            if (vols[i] <= 0) {
                // Degenerate input: fall back to equal weight.
                java.util.Arrays.fill(inv, 1.0 / vols.length);
                return inv;
            }
            inv[i] = 1 / vols[i];
            sum += inv[i];
        }
        for (int i = 0; i < inv.length; i++) {
            inv[i] /= sum;
        }
        return inv;
    }

    /** Leverage multiplier that scales current volatility to the target. */
    public static double volatilityTargetLeverage(double currentAnnualVol, double targetAnnualVol) {
        if (currentAnnualVol <= 0) {
            return 0;
        }
        return targetAnnualVol / currentAnnualVol;
    }
}
