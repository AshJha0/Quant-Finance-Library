package com.quantfinlib.risk;

import com.quantfinlib.util.MathUtils;

/**
 * Correlation and covariance matrices from a returns matrix laid out as
 * {@code returns[asset][time]}.
 */
public final class CorrelationMatrix {

    private CorrelationMatrix() {
    }

    public static double[][] correlation(double[][] returns) {
        int n = returns.length;
        double[][] m = new double[n][n];
        for (int i = 0; i < n; i++) {
            m[i][i] = 1.0;
            for (int j = i + 1; j < n; j++) {
                double c = MathUtils.correlation(returns[i], returns[j]);
                m[i][j] = c;
                m[j][i] = c;
            }
        }
        return m;
    }

    public static double[][] covariance(double[][] returns) {
        int n = returns.length;
        double[][] m = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = i; j < n; j++) {
                double c = MathUtils.covariance(returns[i], returns[j]);
                m[i][j] = c;
                m[j][i] = c;
            }
        }
        return m;
    }
}
