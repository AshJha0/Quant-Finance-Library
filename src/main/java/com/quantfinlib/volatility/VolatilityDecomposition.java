package com.quantfinlib.volatility;

import com.quantfinlib.util.MathUtils;

/**
 * Systematic vs IDIOSYNCRATIC volatility — the decomposition behind
 * "how much of this stock's risk is the market, and how much is the
 * company?" A single-factor (CAPM-style) regression of asset returns on
 * market returns splits total variance EXACTLY:
 *
 * <pre>  Var(asset) = β²·Var(market)  +  Var(residual)
 *               └── systematic ──┘   └ idiosyncratic ┘</pre>
 *
 * with {@code β = Cov(asset, market)/Var(market)} — the split is exact
 * (not approximate) because OLS residuals are uncorrelated with the
 * regressor by construction. The two halves behave differently and
 * deserve different treatment: systematic volatility (rates, inflation,
 * macro shocks) cannot be diversified away and is hedgeable with index
 * instruments; idiosyncratic volatility (earnings, management, product
 * launches) diversifies across names and is exactly what single-name
 * hedges, pairs trades and the CRB's per-symbol factors carry.
 *
 * <p>R² is the systematic SHARE — a utility at 0.7 is mostly a market
 * proxy; a biotech at 0.05 is mostly its own story. All variances are
 * per-period (annualize with {@code ×periodsPerYear}, vols with the
 * square root — the record has helpers). Sample moments (n−1)
 * throughout. Static, deterministic, research lane; pairs with
 * {@code risk.RiskMetrics.beta} (same β, cross-checked in tests) and
 * {@code alpha.PortfolioConstruction}'s beta-neutralization.</p>
 */
public final class VolatilityDecomposition {

    /**
     * @param beta                   Cov(a,m)/Var(m)
     * @param totalVariance          per-period Var(asset)
     * @param systematicVariance     β²·Var(market)
     * @param idiosyncraticVariance  the residual: total − systematic (≥ 0)
     * @param rSquared               systematic share of total, in [0, 1]
     */
    public record Decomposition(double beta, double totalVariance,
                                double systematicVariance,
                                double idiosyncraticVariance, double rSquared) {

        /** Annualized systematic volatility. */
        public double systematicVol(int periodsPerYear) {
            return Math.sqrt(systematicVariance * periodsPerYear);
        }

        /** Annualized idiosyncratic volatility. */
        public double idiosyncraticVol(int periodsPerYear) {
            return Math.sqrt(idiosyncraticVariance * periodsPerYear);
        }

        /** Annualized total volatility. */
        public double totalVol(int periodsPerYear) {
            return Math.sqrt(totalVariance * periodsPerYear);
        }
    }

    private VolatilityDecomposition() {
    }

    /**
     * Decomposes an asset's variance against a market/benchmark series.
     *
     * @param assetReturns  per-period returns, ≥ 30 finite observations
     * @param marketReturns aligned benchmark returns (must carry variance
     *                      — a flat benchmark decomposes nothing)
     */
    public static Decomposition decompose(double[] assetReturns, double[] marketReturns) {
        if (assetReturns.length != marketReturns.length || assetReturns.length < 30) {
            throw new IllegalArgumentException(
                    "need >= 30 aligned observations, got " + assetReturns.length);
        }
        for (int i = 0; i < assetReturns.length; i++) {
            if (!Double.isFinite(assetReturns[i]) || !Double.isFinite(marketReturns[i])) {
                throw new IllegalArgumentException("returns must be finite");
            }
        }
        double marketVar = MathUtils.variance(marketReturns);
        if (!(marketVar > 0)) {
            throw new IllegalArgumentException(
                    "the benchmark carries no variance — nothing to decompose against");
        }
        double cov = MathUtils.covariance(assetReturns, marketReturns);
        double beta = cov / marketVar;
        double totalVar = MathUtils.variance(assetReturns);
        double systematic = beta * beta * marketVar;      // = cov²/Var(m)
        // Exact by the OLS identity; the max() only absorbs float dust
        // (Cauchy-Schwarz guarantees systematic <= total in exact math).
        double idiosyncratic = Math.max(0, totalVar - systematic);
        double rSquared = totalVar > 0 ? Math.min(1, systematic / totalVar) : 0;
        return new Decomposition(beta, totalVar, systematic, idiosyncratic, rSquared);
    }
}
