package com.quantfinlib.hedging;

import com.quantfinlib.util.MathUtils;

/**
 * Minimum-variance and beta hedging with futures or a correlated proxy:
 *
 * <ul>
 *   <li><b>Optimal hedge ratio</b> {@code h* = cov(asset, hedge) / var(hedge)}
 *       — the classic OLS/minimum-variance ratio.</li>
 *   <li><b>Hedge effectiveness</b> — the fraction of variance removed at the
 *       optimal ratio (= correlation², the standard 80%+ effectiveness test).</li>
 *   <li><b>Futures contract sizing</b> — contracts to move a portfolio from
 *       its current beta to a target beta.</li>
 * </ul>
 */
public final class MinimumVarianceHedge {

    private MinimumVarianceHedge() {
    }

    /** Optimal (variance-minimizing) hedge ratio: units of hedge per unit of asset. */
    public static double hedgeRatio(double[] assetReturns, double[] hedgeReturns) {
        double var = MathUtils.variance(hedgeReturns);
        if (var == 0) {
            return 0;
        }
        return MathUtils.covariance(assetReturns, hedgeReturns) / var;
    }

    /**
     * Fraction of asset variance eliminated at the optimal hedge ratio
     * (equals the squared correlation).
     */
    public static double hedgeEffectiveness(double[] assetReturns, double[] hedgeReturns) {
        double rho = MathUtils.correlation(assetReturns, hedgeReturns);
        return rho * rho;
    }

    /** Return series of the hedged position: asset - ratio × hedge. */
    public static double[] hedgedReturns(double[] assetReturns, double[] hedgeReturns, double ratio) {
        double[] out = new double[assetReturns.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = assetReturns[i] - ratio * hedgeReturns[i];
        }
        return out;
    }

    /** Realized variance reduction of a given hedge ratio versus unhedged. */
    public static double varianceReduction(double[] assetReturns, double[] hedgeReturns, double ratio) {
        double unhedged = MathUtils.variance(assetReturns);
        if (unhedged == 0) {
            return 0;
        }
        return 1 - MathUtils.variance(hedgedReturns(assetReturns, hedgeReturns, ratio)) / unhedged;
    }

    /**
     * Futures contracts (negative = sell) to shift a portfolio from
     * {@code currentBeta} to {@code targetBeta}:
     * {@code N = (targetBeta - currentBeta) * V / (F * multiplier)}.
     */
    public static double betaAdjustmentContracts(double currentBeta, double targetBeta,
                                                 double portfolioValue,
                                                 double futuresPrice, double contractMultiplier) {
        return (targetBeta - currentBeta) * portfolioValue / (futuresPrice * contractMultiplier);
    }

    /** Contracts to fully hedge (target beta 0) — negative = sell futures. */
    public static double fullHedgeContracts(double beta, double portfolioValue,
                                            double futuresPrice, double contractMultiplier) {
        return betaAdjustmentContracts(beta, 0, portfolioValue, futuresPrice, contractMultiplier);
    }
}
