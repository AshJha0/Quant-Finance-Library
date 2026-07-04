package com.quantfinlib.risk;

import com.quantfinlib.util.MathUtils;

/**
 * VaR model validation: do the exceptions (losses beyond VaR) occur at the
 * promised rate, and independently?
 *
 * <ul>
 *   <li><b>Kupiec POF</b> — likelihood-ratio test that the exception
 *       frequency matches {@code 1 - confidence} (two-sided: both too many
 *       and too few exceptions reject). χ²(1).</li>
 *   <li><b>Christoffersen independence</b> — LR test that exceptions do not
 *       cluster (a model that is right on average but wrong in crises fails
 *       here). χ²(1).</li>
 *   <li><b>Conditional coverage</b> — the joint test (POF + independence),
 *       χ²(2), with the exact p-value {@code e^{-LR/2}}.</li>
 * </ul>
 *
 * A p-value below the chosen significance (e.g. 0.05) rejects the model.
 */
public final class VarBacktest {

    public record VarBacktestResult(
            int observations,
            int exceptions,
            double expectedExceptions,
            double exceptionRate,
            double kupiecStatistic,
            double kupiecPValue,
            double independenceStatistic,
            double independencePValue,
            double conditionalCoverageStatistic,
            double conditionalCoveragePValue) {

        /** Exception frequency consistent with the confidence level? */
        public boolean calibrated(double significance) {
            return kupiecPValue > significance;
        }

        /** Exceptions arrive independently (no crisis clustering)? */
        public boolean independent(double significance) {
            return independencePValue > significance;
        }

        /** Joint test: right rate AND independent. */
        public boolean passes(double significance) {
            return conditionalCoveragePValue > significance;
        }
    }

    private VarBacktest() {
    }

    /** Backtests a constant VaR (positive loss fraction) against realized returns. */
    public static VarBacktestResult test(double[] returns, double constantVar, double confidence) {
        double[] var = new double[returns.length];
        java.util.Arrays.fill(var, constantVar);
        return test(returns, var, confidence);
    }

    /**
     * @param returns      realized periodic returns
     * @param varForecasts VaR forecast for each period as a positive loss
     *                     fraction, aligned with {@code returns}
     * @param confidence   the VaR confidence level (e.g. 0.95, 0.99)
     */
    public static VarBacktestResult test(double[] returns, double[] varForecasts,
                                         double confidence) {
        if (returns.length != varForecasts.length || returns.length < 20) {
            throw new IllegalArgumentException("need >= 20 aligned returns/forecasts");
        }
        if (confidence <= 0 || confidence >= 1) {
            throw new IllegalArgumentException("confidence must be in (0,1)");
        }
        int n = returns.length;
        boolean[] exception = new boolean[n];
        int x = 0;
        for (int i = 0; i < n; i++) {
            exception[i] = returns[i] < -varForecasts[i];
            if (exception[i]) {
                x++;
            }
        }
        double p = 1 - confidence;

        // Kupiec proportion-of-failures LR.
        double observedRate = (double) x / n;
        double llNull = xLnP(n - x, 1 - p) + xLnP(x, p);
        double llAlt = xLnP(n - x, 1 - observedRate) + xLnP(x, observedRate);
        double kupiec = Math.max(0, 2 * (llAlt - llNull));
        double kupiecP = chiSquare1PValue(kupiec);

        // Christoffersen independence LR over exception transitions.
        long n00 = 0, n01 = 0, n10 = 0, n11 = 0;
        for (int i = 1; i < n; i++) {
            if (!exception[i - 1] && !exception[i]) {
                n00++;
            } else if (!exception[i - 1]) {
                n01++;
            } else if (!exception[i]) {
                n10++;
            } else {
                n11++;
            }
        }
        double independence = 0;
        long transitions = n00 + n01 + n10 + n11;
        long exceptionsAfter = n01 + n11;
        if (transitions > 0 && exceptionsAfter > 0 && exceptionsAfter < transitions) {
            double pi = (double) exceptionsAfter / transitions;
            double pi01 = n00 + n01 == 0 ? 0 : (double) n01 / (n00 + n01);
            double pi11 = n10 + n11 == 0 ? 0 : (double) n11 / (n10 + n11);
            double llPooled = xLnP(n00 + n10, 1 - pi) + xLnP(n01 + n11, pi);
            double llMarkov = xLnP(n00, 1 - pi01) + xLnP(n01, pi01)
                    + xLnP(n10, 1 - pi11) + xLnP(n11, pi11);
            independence = Math.max(0, 2 * (llMarkov - llPooled));
        }
        double independenceP = chiSquare1PValue(independence);

        double cc = kupiec + independence;
        double ccP = Math.exp(-cc / 2);   // exact chi-square(2) survival

        return new VarBacktestResult(n, x, p * n, observedRate,
                kupiec, kupiecP, independence, independenceP, cc, ccP);
    }

    /** {@code count · ln(probability)} with the 0·ln(0) = 0 convention. */
    private static double xLnP(long count, double probability) {
        if (count == 0) {
            return 0;
        }
        if (probability <= 0) {
            return Double.NEGATIVE_INFINITY;
        }
        return count * Math.log(probability);
    }

    /** χ²(1) survival function via the normal CDF. */
    private static double chiSquare1PValue(double statistic) {
        if (statistic <= 0) {
            return 1;
        }
        return 2 * (1 - MathUtils.normCdf(Math.sqrt(statistic)));
    }
}
