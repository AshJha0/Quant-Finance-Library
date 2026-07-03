package com.quantfinlib.hedging;

import com.quantfinlib.util.MathUtils;

/**
 * Statistical (pairs) hedging: regresses one price series on another to get
 * the hedge ratio, builds the spread, and characterizes its mean reversion —
 * z-score for entry/exit signals and half-life (from an AR(1) fit of spread
 * changes on spread levels) for expected holding time.
 */
public final class PairsHedger {

    public record PairsAnalysis(
            double hedgeRatio,       // units of B per unit of A (OLS beta)
            double intercept,
            double correlation,      // return correlation of the pair
            double halfLifeBars,     // mean-reversion half-life (+INF if not mean-reverting)
            double lastZScore,       // current spread z-score
            double[] spread) {       // spread series: A - ratio*B - intercept
    }

    private PairsHedger() {
    }

    public static PairsAnalysis analyze(double[] pricesA, double[] pricesB) {
        if (pricesA.length != pricesB.length || pricesA.length < 20) {
            throw new IllegalArgumentException("need two aligned series of >= 20 points");
        }
        // OLS of A on B (price levels) for the hedge ratio.
        double varB = MathUtils.variance(pricesB);
        double beta = varB == 0 ? 0 : MathUtils.covariance(pricesA, pricesB) / varB;
        double alpha = MathUtils.mean(pricesA) - beta * MathUtils.mean(pricesB);

        int n = pricesA.length;
        double[] spread = new double[n];
        for (int i = 0; i < n; i++) {
            spread[i] = pricesA[i] - beta * pricesB[i] - alpha;
        }

        double mean = MathUtils.mean(spread);
        double std = MathUtils.stdDev(spread);
        double z = std == 0 ? 0 : (spread[n - 1] - mean) / std;

        // Return correlation of the pair.
        double[] retA = new double[n - 1];
        double[] retB = new double[n - 1];
        for (int i = 1; i < n; i++) {
            retA[i - 1] = pricesA[i] / pricesA[i - 1] - 1;
            retB[i - 1] = pricesB[i] / pricesB[i - 1] - 1;
        }

        return new PairsAnalysis(beta, alpha, MathUtils.correlation(retA, retB),
                halfLife(spread), z, spread);
    }

    /**
     * Mean-reversion half-life in bars from the AR(1)-style regression
     * {@code Δs(t) = c + λ·s(t-1)}: half-life = -ln(2)/λ.
     * Returns +INF when the spread shows no mean reversion (λ >= 0).
     */
    public static double halfLife(double[] spread) {
        int n = spread.length;
        double[] lag = new double[n - 1];
        double[] diff = new double[n - 1];
        for (int i = 1; i < n; i++) {
            lag[i - 1] = spread[i - 1];
            diff[i - 1] = spread[i] - spread[i - 1];
        }
        double varLag = MathUtils.variance(lag);
        if (varLag == 0) {
            return Double.POSITIVE_INFINITY;
        }
        double lambda = MathUtils.covariance(diff, lag) / varLag;
        if (lambda >= 0) {
            return Double.POSITIVE_INFINITY;
        }
        return -Math.log(2) / lambda;
    }
}
