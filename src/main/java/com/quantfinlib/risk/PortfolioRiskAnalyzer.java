package com.quantfinlib.risk;

import com.quantfinlib.util.MathUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Portfolio-level risk engine: portfolio VaR/CVaR/volatility, asset-level
 * risk, exposure analysis, correlation analysis, and risk decomposition
 * (marginal contribution to risk).
 */
public final class PortfolioRiskAnalyzer {

    /**
     * Full risk report. Volatilities are annualized; VaR/CVaR are per-period
     * positive loss fractions; risk contributions sum to 1.
     */
    public record RiskReport(
            double annualizedVolatility,
            double valueAtRisk,
            double conditionalValueAtRisk,
            double parametricVaR,
            double sharpeRatio,
            Map<String, Double> exposures,
            Map<String, Double> assetVolatilities,
            Map<String, Double> assetVaR,
            Map<String, Double> riskContributions,
            double[][] correlationMatrix) {
    }

    private final String[] symbols;
    private final double[][] assetReturns;   // [asset][time]
    private final double[] weights;

    public PortfolioRiskAnalyzer(String[] symbols, double[][] assetReturns, double[] weights) {
        if (symbols.length != assetReturns.length || symbols.length != weights.length) {
            throw new IllegalArgumentException("symbols, returns and weights must have equal length");
        }
        this.symbols = symbols.clone();
        this.assetReturns = assetReturns;
        this.weights = weights.clone();
    }

    /** Weighted portfolio return series. */
    public double[] portfolioReturns() {
        int t = assetReturns[0].length;
        double[] out = new double[t];
        for (int i = 0; i < t; i++) {
            double r = 0;
            for (int a = 0; a < weights.length; a++) {
                r += weights[a] * assetReturns[a][i];
            }
            out[i] = r;
        }
        return out;
    }

    public RiskReport analyze(double confidence, int periodsPerYear) {
        double[] pr = portfolioReturns();
        double[][] cov = CorrelationMatrix.covariance(assetReturns);

        Map<String, Double> exposures = new LinkedHashMap<>();
        Map<String, Double> assetVols = new LinkedHashMap<>();
        Map<String, Double> assetVaR = new LinkedHashMap<>();
        for (int a = 0; a < symbols.length; a++) {
            exposures.put(symbols[a], weights[a]);
            assetVols.put(symbols[a], RiskMetrics.annualizedVolatility(assetReturns[a], periodsPerYear));
            assetVaR.put(symbols[a], RiskMetrics.historicalVar(assetReturns[a], confidence));
        }

        return new RiskReport(
                RiskMetrics.annualizedVolatility(pr, periodsPerYear),
                RiskMetrics.historicalVar(pr, confidence),
                RiskMetrics.conditionalVar(pr, confidence),
                RiskMetrics.parametricVar(pr, confidence),
                RiskMetrics.sharpeRatio(pr, 0.0, periodsPerYear),
                exposures,
                assetVols,
                assetVaR,
                riskContributions(cov),
                CorrelationMatrix.correlation(assetReturns));
    }

    /**
     * Risk decomposition: each asset's fractional contribution to total
     * portfolio variance ({@code w_i * (Cov·w)_i / (w'·Cov·w)}). Contributions
     * sum to 1 for a non-degenerate portfolio.
     */
    public Map<String, Double> riskContributions(double[][] covariance) {
        double[] covW = MathUtils.matVec(covariance, weights);
        double portVar = MathUtils.dot(weights, covW);
        Map<String, Double> rc = new LinkedHashMap<>();
        for (int a = 0; a < symbols.length; a++) {
            rc.put(symbols[a], portVar == 0 ? 0 : weights[a] * covW[a] / portVar);
        }
        return rc;
    }
}
