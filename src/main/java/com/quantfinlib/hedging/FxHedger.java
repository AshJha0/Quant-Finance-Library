package com.quantfinlib.hedging;

import com.quantfinlib.util.MathUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FX exposure hedging with forwards: nets currency exposures across a book,
 * computes the variance-minimizing hedge ratio for a foreign-asset position,
 * and prices the carry cost of the forward hedge from forward points.
 */
public final class FxHedger {

    /** One currency exposure, signed, in base-currency terms. */
    public record FxExposure(String currency, double amountBase) {
    }

    private FxHedger() {
    }

    /** Nets signed exposures per currency across the book. */
    public static Map<String, Double> netExposures(List<FxExposure> exposures) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (FxExposure e : exposures) {
            out.merge(e.currency(), e.amountBase(), Double::sum);
        }
        return out;
    }

    /**
     * Variance-minimizing hedge ratio for a foreign asset held by a
     * base-currency investor: {@code h* = cov(unhedged, fx) / var(fx)}.
     * 1 = full hedge is optimal; below 1 when the asset and the currency are
     * negatively correlated (the currency already diversifies).
     *
     * @param unhedgedReturnsBase asset returns measured in the base currency
     * @param fxReturns           returns of the foreign currency vs base
     */
    public static double optimalHedgeRatio(double[] unhedgedReturnsBase, double[] fxReturns) {
        double var = MathUtils.variance(fxReturns);
        if (var == 0) {
            return 0;
        }
        return MathUtils.covariance(unhedgedReturnsBase, fxReturns) / var;
    }

    /** Return series with a fraction {@code hedgeRatio} of the FX exposure hedged away. */
    public static double[] hedgedReturns(double[] unhedgedReturnsBase, double[] fxReturns,
                                         double hedgeRatio) {
        double[] out = new double[unhedgedReturnsBase.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = unhedgedReturnsBase[i] - hedgeRatio * fxReturns[i];
        }
        return out;
    }

    /**
     * Annualized carry of the forward hedge in bps: positive = hedging costs
     * carry (forward above spot for the currency you sell), negative = carry
     * pickup.
     */
    public static double forwardCarryBps(double spot, double forward, double tenorYears) {
        return (forward / spot - 1) / tenorYears * 1e4;
    }

    /** Notional of forwards to sell (negative = buy) for a target hedge ratio. */
    public static double hedgeNotional(double netExposureBase, double hedgeRatio) {
        return netExposureBase * hedgeRatio;
    }
}
