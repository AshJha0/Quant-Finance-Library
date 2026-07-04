package com.quantfinlib.ml;

import com.quantfinlib.util.MathUtils;

/**
 * Two-state Gaussian Markov-switching model (hidden Markov model) fitted by
 * Baum-Welch EM with forward-backward scaling: detects calm/turbulent
 * regimes in a return series. State 1 is always the <b>high-volatility</b>
 * regime. Feeds naturally into vol targeting (de-lever when
 * {@code smoothedHighVolProbability} rises) and liquidity forecasting.
 */
public final class RegimeDetector {

    public record RegimeModel(
            double[] means,                      // per-state mean return
            double[] stdDevs,                    // per-state volatility (state 1 = high vol)
            double[][] transition,               // transition[i][j] = P(next=j | now=i)
            double logLikelihood,
            double[] smoothedHighVolProbability, // P(high vol at t | all data)
            double[] currentProbabilities,       // filtered P(state | data up to T)
            int currentRegime) {                 // argmax of currentProbabilities

        /** Expected persistence of a regime in periods: 1 / (1 - p_stay). */
        public double expectedDuration(int state) {
            return 1 / (1 - transition[state][state]);
        }
    }

    private RegimeDetector() {
    }

    public static RegimeModel fit(double[] returns, int maxIterations) {
        int n = returns.length;
        if (n < 100) {
            throw new IllegalArgumentException("need at least 100 returns, got " + n);
        }
        // Initialize by splitting on absolute-return median.
        double[] abs = new double[n];
        for (int t = 0; t < n; t++) {
            abs[t] = Math.abs(returns[t]);
        }
        double median = MathUtils.percentile(abs, 0.5);
        double mean = MathUtils.mean(returns);
        double lowVar = 0, highVar = 0;
        int lowCount = 0, highCount = 0;
        for (int t = 0; t < n; t++) {
            double d = returns[t] - mean;
            if (abs[t] <= median) {
                lowVar += d * d;
                lowCount++;
            } else {
                highVar += d * d;
                highCount++;
            }
        }
        double[] mu = {mean, mean};
        double[] variance = {Math.max(lowVar / Math.max(1, lowCount), 1e-12),
                Math.max(highVar / Math.max(1, highCount), 1e-12)};
        double[][] a = {{0.95, 0.05}, {0.05, 0.95}};
        double[] pi = {0.5, 0.5};

        double[][] alpha = new double[n][2];
        double[][] beta = new double[n][2];
        double[][] gamma = new double[n][2];
        double[] scale = new double[n];
        double logLikelihood = Double.NEGATIVE_INFINITY;

        for (int iter = 0; iter < maxIterations; iter++) {
            // Forward pass with scaling.
            double ll = 0;
            for (int t = 0; t < n; t++) {
                double sum = 0;
                for (int j = 0; j < 2; j++) {
                    double prior = t == 0
                            ? pi[j]
                            : alpha[t - 1][0] * a[0][j] + alpha[t - 1][1] * a[1][j];
                    alpha[t][j] = prior * density(returns[t], mu[j], variance[j]);
                    sum += alpha[t][j];
                }
                scale[t] = sum <= 0 ? 1e-300 : sum;
                alpha[t][0] /= scale[t];
                alpha[t][1] /= scale[t];
                ll += Math.log(scale[t]);
            }
            // Backward pass (same scaling).
            beta[n - 1][0] = 1;
            beta[n - 1][1] = 1;
            for (int t = n - 2; t >= 0; t--) {
                for (int i = 0; i < 2; i++) {
                    double sum = 0;
                    for (int j = 0; j < 2; j++) {
                        sum += a[i][j] * density(returns[t + 1], mu[j], variance[j]) * beta[t + 1][j];
                    }
                    beta[t][i] = sum / scale[t + 1];
                }
            }
            // Smoothed state probabilities and transition statistics.
            double[][] xiSum = new double[2][2];
            double[] gammaSum = new double[2];
            for (int t = 0; t < n; t++) {
                double norm = alpha[t][0] * beta[t][0] + alpha[t][1] * beta[t][1];
                gamma[t][0] = alpha[t][0] * beta[t][0] / norm;
                gamma[t][1] = 1 - gamma[t][0];
                if (t < n - 1) {
                    gammaSum[0] += gamma[t][0];
                    gammaSum[1] += gamma[t][1];
                    double denom = 0;
                    double[][] xi = new double[2][2];
                    for (int i = 0; i < 2; i++) {
                        for (int j = 0; j < 2; j++) {
                            xi[i][j] = alpha[t][i] * a[i][j]
                                    * density(returns[t + 1], mu[j], variance[j]) * beta[t + 1][j];
                            denom += xi[i][j];
                        }
                    }
                    for (int i = 0; i < 2; i++) {
                        for (int j = 0; j < 2; j++) {
                            xiSum[i][j] += xi[i][j] / denom;
                        }
                    }
                }
            }
            // M-step.
            pi[0] = gamma[0][0];
            pi[1] = gamma[0][1];
            for (int i = 0; i < 2; i++) {
                double rowSum = xiSum[i][0] + xiSum[i][1];
                if (rowSum > 0) {
                    a[i][0] = xiSum[i][0] / rowSum;
                    a[i][1] = xiSum[i][1] / rowSum;
                }
            }
            for (int j = 0; j < 2; j++) {
                double weight = 0, weightedSum = 0;
                for (int t = 0; t < n; t++) {
                    weight += gamma[t][j];
                    weightedSum += gamma[t][j] * returns[t];
                }
                mu[j] = weightedSum / weight;
                double varSum = 0;
                for (int t = 0; t < n; t++) {
                    double d = returns[t] - mu[j];
                    varSum += gamma[t][j] * d * d;
                }
                variance[j] = Math.max(varSum / weight, 1e-14);
            }
            if (Math.abs(ll - logLikelihood) < 1e-9) {
                logLikelihood = ll;
                break;
            }
            logLikelihood = ll;
        }

        // Canonical ordering: state 1 = high volatility.
        if (variance[0] > variance[1]) {
            swap(mu);
            swap(variance);
            double tmp = a[0][0]; a[0][0] = a[1][1]; a[1][1] = tmp;
            tmp = a[0][1]; a[0][1] = a[1][0]; a[1][0] = tmp;
            for (int t = 0; t < n; t++) {
                double g = gamma[t][0]; gamma[t][0] = gamma[t][1]; gamma[t][1] = g;
                double al = alpha[t][0]; alpha[t][0] = alpha[t][1]; alpha[t][1] = al;
            }
        }
        double[] highVolProb = new double[n];
        for (int t = 0; t < n; t++) {
            highVolProb[t] = gamma[t][1];
        }
        double[] current = {alpha[n - 1][0], alpha[n - 1][1]};
        return new RegimeModel(mu, new double[]{Math.sqrt(variance[0]), Math.sqrt(variance[1])},
                a, logLikelihood, highVolProb, current, current[1] > current[0] ? 1 : 0);
    }

    private static double density(double x, double mean, double variance) {
        double d = x - mean;
        return Math.exp(-0.5 * d * d / variance) / Math.sqrt(2 * Math.PI * variance);
    }

    private static void swap(double[] pair) {
        double tmp = pair[0];
        pair[0] = pair[1];
        pair[1] = tmp;
    }
}
