package com.quantfinlib.risk;

import com.quantfinlib.stats.LinearAlgebra;
import com.quantfinlib.util.ValueArrays;

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
     * positive loss fractions (VaR the interpolated historical quantile,
     * CVaR the Acerbi-Tasche tail mean of {@link TailRisk}); risk
     * contributions sum to 1. The constructor copies the return matrix and
     * requires equal-length, finite rows.
     */
    public static final class RiskReport {
        private final double annualizedVolatility;
        private final double valueAtRisk;
        private final double conditionalValueAtRisk;
        private final double parametricVaR;
        private final double sharpeRatio;
        private final Map<String, Double> exposures;
        private final Map<String, Double> assetVolatilities;
        private final Map<String, Double> assetVaR;
        private final Map<String, Double> riskContributions;
        private final double[][] correlationMatrix;

        /**
         * Creates the value; array and collection arguments are copied.
         */
        public RiskReport(double annualizedVolatility, double valueAtRisk, double conditionalValueAtRisk,
                double parametricVaR, double sharpeRatio, Map<String, Double> exposures,
                Map<String, Double> assetVolatilities, Map<String, Double> assetVaR,
                Map<String, Double> riskContributions, double[][] correlationMatrix) {
            this.annualizedVolatility = annualizedVolatility;
            this.valueAtRisk = valueAtRisk;
            this.conditionalValueAtRisk = conditionalValueAtRisk;
            this.parametricVaR = parametricVaR;
            this.sharpeRatio = sharpeRatio;
            this.exposures = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(exposures));
            this.assetVolatilities = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(assetVolatilities));
            this.assetVaR = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(assetVaR));
            this.riskContributions = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(riskContributions));
            this.correlationMatrix = ValueArrays.copy(correlationMatrix);
        }

        /** {@code annualizedVolatility}. */
        public double annualizedVolatility() {
            return annualizedVolatility;
        }

        /** {@code valueAtRisk}. */
        public double valueAtRisk() {
            return valueAtRisk;
        }

        /** {@code conditionalValueAtRisk}. */
        public double conditionalValueAtRisk() {
            return conditionalValueAtRisk;
        }

        /** {@code parametricVaR}. */
        public double parametricVaR() {
            return parametricVaR;
        }

        /** {@code sharpeRatio}. */
        public double sharpeRatio() {
            return sharpeRatio;
        }

        /** {@code exposures}. */
        public Map<String, Double> exposures() {
            return exposures;
        }

        /** {@code assetVolatilities}. */
        public Map<String, Double> assetVolatilities() {
            return assetVolatilities;
        }

        /** {@code assetVaR}. */
        public Map<String, Double> assetVaR() {
            return assetVaR;
        }

        /** {@code riskContributions}. */
        public Map<String, Double> riskContributions() {
            return riskContributions;
        }

        /** Element {@code [i][j]} of {@code correlationMatrix}. */
        public double correlationMatrix(int i, int j) {
            return correlationMatrix[i][j];
        }

        /** Number of rows of {@code correlationMatrix}. */
        public int correlationMatrixRows() {
            return correlationMatrix.length;
        }

        /** A deep copy of {@code correlationMatrix}. */
        public double[][] correlationMatrixCopy() {
            return ValueArrays.copy(correlationMatrix);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof RiskReport o)) {
                return false;
            }
            return Double.compare(annualizedVolatility, o.annualizedVolatility) == 0
                    && Double.compare(valueAtRisk, o.valueAtRisk) == 0
                    && Double.compare(conditionalValueAtRisk, o.conditionalValueAtRisk) == 0
                    && Double.compare(parametricVaR, o.parametricVaR) == 0
                    && Double.compare(sharpeRatio, o.sharpeRatio) == 0
                    && java.util.Objects.equals(exposures, o.exposures)
                    && java.util.Objects.equals(assetVolatilities, o.assetVolatilities)
                    && java.util.Objects.equals(assetVaR, o.assetVaR)
                    && java.util.Objects.equals(riskContributions, o.riskContributions)
                    && java.util.Arrays.deepEquals(correlationMatrix, o.correlationMatrix);
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + Double.hashCode(annualizedVolatility);
            h = 31 * h + Double.hashCode(valueAtRisk);
            h = 31 * h + Double.hashCode(conditionalValueAtRisk);
            h = 31 * h + Double.hashCode(parametricVaR);
            h = 31 * h + Double.hashCode(sharpeRatio);
            h = 31 * h + java.util.Objects.hashCode(exposures);
            h = 31 * h + java.util.Objects.hashCode(assetVolatilities);
            h = 31 * h + java.util.Objects.hashCode(assetVaR);
            h = 31 * h + java.util.Objects.hashCode(riskContributions);
            h = 31 * h + java.util.Arrays.deepHashCode(correlationMatrix);
            return h;
        }

        @Override
        public String toString() {
            return "RiskReport[" + "annualizedVolatility=" + annualizedVolatility + ", " + "valueAtRisk=" + valueAtRisk + ", " + "conditionalValueAtRisk=" + conditionalValueAtRisk + ", " + "parametricVaR=" + parametricVaR + ", " + "sharpeRatio=" + sharpeRatio + ", " + "exposures=" + exposures + ", " + "assetVolatilities=" + assetVolatilities + ", " + "assetVaR=" + assetVaR + ", " + "riskContributions=" + riskContributions + ", " + "correlationMatrix=" + java.util.Arrays.deepToString(correlationMatrix) + "]";
        }
    }

    private final String[] symbols;
    private final double[][] assetReturns;   // [asset][time]
    private final double[] weights;

    public PortfolioRiskAnalyzer(String[] symbols, double[][] assetReturns, double[] weights) {
        if (symbols.length != assetReturns.length || symbols.length != weights.length) {
            throw new IllegalArgumentException("symbols, returns and weights must have equal length");
        }
        if (symbols.length == 0) {
            throw new IllegalArgumentException("need at least one asset");
        }
        int t = assetReturns[0].length;
        this.assetReturns = new double[assetReturns.length][];
        for (int a = 0; a < assetReturns.length; a++) {
            if (assetReturns[a].length != t) {
                throw new IllegalArgumentException("asset " + a + " has " + assetReturns[a].length
                        + " returns, asset 0 has " + t);
            }
            for (double r : assetReturns[a]) {
                if (!Double.isFinite(r)) {
                    throw new IllegalArgumentException("non-finite return for asset " + a);
                }
            }
            this.assetReturns[a] = assetReturns[a].clone();
        }
        for (double w : weights) {
            if (!Double.isFinite(w)) {
                throw new IllegalArgumentException("weights must be finite");
            }
        }
        this.symbols = symbols.clone();
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

    /** {@link #analyze(double, int, double)} with a zero risk-free rate. */
    public RiskReport analyze(double confidence, int periodsPerYear) {
        return analyze(confidence, periodsPerYear, 0.0);
    }

    /**
     * Computes the risk report.
     *
     * @param riskFreeRate annual risk-free rate used in the Sharpe ratio
     */
    public RiskReport analyze(double confidence, int periodsPerYear, double riskFreeRate) {
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
                RiskMetrics.sharpeRatio(pr, riskFreeRate, periodsPerYear),
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
        double[] covW = LinearAlgebra.matVec(covariance, weights);
        double portVar = LinearAlgebra.dot(weights, covW);
        Map<String, Double> rc = new LinkedHashMap<>();
        for (int a = 0; a < symbols.length; a++) {
            rc.put(symbols[a], portVar == 0 ? 0 : weights[a] * covW[a] / portVar);
        }
        return rc;
    }
}
