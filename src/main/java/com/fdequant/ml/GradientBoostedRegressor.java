package com.fdequant.ml;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Gradient-boosted regression over decision stumps (XGBoost-style additive
 * boosting with squared-error loss), implemented in pure Java with no
 * dependencies. Suited to small/medium tabular problems such as risk
 * forecasting features.
 */
public final class GradientBoostedRegressor {

    private record Stump(int feature, double threshold, double leftValue, double rightValue) {
        double predict(double[] x) {
            return x[feature] <= threshold ? leftValue : rightValue;
        }
    }

    private final int rounds;
    private final double learningRate;
    private final List<Stump> stumps = new ArrayList<>();
    private double baseline;
    private boolean fitted;

    public GradientBoostedRegressor(int rounds, double learningRate) {
        this.rounds = rounds;
        this.learningRate = learningRate;
    }

    public static GradientBoostedRegressor withDefaults() {
        return new GradientBoostedRegressor(200, 0.1);
    }

    /** Fits the model on {@code x[sample][feature]} / {@code y[sample]}. */
    public GradientBoostedRegressor fit(double[][] x, double[] y) {
        int n = y.length;
        if (n == 0 || x.length != n) {
            throw new IllegalArgumentException("x/y size mismatch or empty");
        }
        stumps.clear();
        baseline = mean(y);
        double[] pred = new double[n];
        Arrays.fill(pred, baseline);

        double[] residual = new double[n];
        for (int r = 0; r < rounds; r++) {
            for (int i = 0; i < n; i++) {
                residual[i] = y[i] - pred[i];
            }
            Stump best = bestStump(x, residual);
            if (best == null) {
                break;
            }
            stumps.add(best);
            for (int i = 0; i < n; i++) {
                pred[i] += learningRate * best.predict(x[i]);
            }
        }
        fitted = true;
        return this;
    }

    public double predict(double[] x) {
        if (!fitted) {
            throw new IllegalStateException("model not fitted");
        }
        double p = baseline;
        for (Stump s : stumps) {
            p += learningRate * s.predict(x);
        }
        return p;
    }

    public double[] predictAll(double[][] x) {
        double[] out = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            out[i] = predict(x[i]);
        }
        return out;
    }

    /** Root mean squared error on a labeled set. */
    public double rmse(double[][] x, double[] y) {
        double s = 0;
        for (int i = 0; i < y.length; i++) {
            double d = predict(x[i]) - y[i];
            s += d * d;
        }
        return Math.sqrt(s / y.length);
    }

    // ------------------------------------------------------------------

    /** Finds the SSE-optimal stump over all features via sorted prefix sums. */
    private static Stump bestStump(double[][] x, double[] residual) {
        int n = residual.length;
        int features = x[0].length;
        double totalSum = 0;
        for (double r : residual) {
            totalSum += r;
        }

        double bestGain = 1e-12;
        Stump best = null;

        Integer[] order = new Integer[n];
        for (int f = 0; f < features; f++) {
            final int ff = f;
            for (int i = 0; i < n; i++) {
                order[i] = i;
            }
            Arrays.sort(order, (a, b) -> Double.compare(x[a][ff], x[b][ff]));

            double leftSum = 0;
            for (int k = 0; k < n - 1; k++) {
                leftSum += residual[order[k]];
                // Only split between distinct feature values.
                if (x[order[k]][f] == x[order[k + 1]][f]) {
                    continue;
                }
                int leftN = k + 1;
                int rightN = n - leftN;
                double rightSum = totalSum - leftSum;
                // Variance-reduction gain of the split (up to constants).
                double gain = leftSum * leftSum / leftN + rightSum * rightSum / rightN
                        - totalSum * totalSum / n;
                if (gain > bestGain) {
                    bestGain = gain;
                    double threshold = (x[order[k]][f] + x[order[k + 1]][f]) / 2;
                    best = new Stump(f, threshold, leftSum / leftN, rightSum / rightN);
                }
            }
        }
        return best;
    }

    private static double mean(double[] v) {
        double s = 0;
        for (double x : v) {
            s += x;
        }
        return s / v.length;
    }
}
