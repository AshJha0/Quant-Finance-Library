package com.quantfinlib.risk;

import com.quantfinlib.stats.SpecialFunctions;

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
 *
 * <p>{@link #trafficLight(int, int)} is the Basel supervisory zone test on the
 * exception count (MAR32, MAR99).</p>
 *
 * <p>{@link #acerbiSzekelyZ2} backtests expected shortfall, which FRTB makes
 * the capital measure and which an exception count alone cannot validate.</p>
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

        /** Joint test: right rate and independent. */
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
     * Backtests a VaR forecast series.
     *
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

    /** Acerbi-Szekely (2014) Z2 at the 5% level, T = 250, 97.5% ES. */
    public static final double Z2_THRESHOLD_5PCT = -0.70;
    /** Acerbi-Szekely (2014) Z2 at the 0.01% level, T = 250, 97.5% ES. */
    public static final double Z2_THRESHOLD_001PCT = -1.80;

    /**
     * The Acerbi-Szekely (2014, "Backtesting expected shortfall", Risk,
     * December) unconditional ES test statistic
     *
     * <pre>  Z2 = 1 − Σ_t L_t·1{L_t &gt; VaR_t} / (T·(1−c)·ES_t)</pre>
     *
     * with losses {@code L_t = −r_t}. Under a correct ES forecast
     * E[Z2] = 0; a negative value means realised tail losses exceed the
     * forecast ES. The paper's simulations give thresholds that are nearly
     * independent of the return distribution for T = 250 and c = 97.5%:
     * {@link #Z2_THRESHOLD_5PCT} = −0.70 and {@link #Z2_THRESHOLD_001PCT} =
     * −1.80 (the analogue of the Basel amber and red zones). For other
     * settings the p-value must be simulated under the model.
     *
     * @param returns      realised returns
     * @param varForecasts VaR forecasts, positive loss fractions
     * @param esForecasts  ES forecasts at the same confidence, positive
     * @param confidence   e.g. 0.975
     */
    public static double acerbiSzekelyZ2(double[] returns, double[] varForecasts,
                                         double[] esForecasts, double confidence) {
        int n = returns.length;
        if (varForecasts.length != n || esForecasts.length != n || n < 1) {
            throw new IllegalArgumentException("need aligned, non-empty returns/VaR/ES");
        }
        if (!(confidence > 0 && confidence < 1)) {
            throw new IllegalArgumentException("confidence must be in (0,1)");
        }
        double alpha = 1 - confidence;
        double sum = 0;
        for (int t = 0; t < n; t++) {
            if (!Double.isFinite(returns[t]) || !Double.isFinite(varForecasts[t])
                    || !(esForecasts[t] > 0) || esForecasts[t] == Double.POSITIVE_INFINITY) {
                throw new IllegalArgumentException("non-finite input or ES <= 0 at " + t);
            }
            double loss = -returns[t];
            if (loss > varForecasts[t]) {
                sum += loss / esForecasts[t];
            }
        }
        return 1 - sum / (n * alpha);
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

    /**
     * χ²(1) survival function, {@code P(chi2_1 > x) = erfc(sqrt(x / 2)) = 2 Phi(-sqrt(x))}.
     * Taking the lower normal tail directly keeps relative accuracy far out:
     * the form {@code 2 (1 - Phi(sqrt x))} used before 3.1 cancelled to 0 once
     * the p-value fell below about 1e-16 (x above about 70), so a badly wrong
     * model reported p = 0. The two forms differ by at most a few 1e-16
     * absolute, so no accept/reject decision at a usual significance level changes.
     */
    static double chiSquare1PValue(double statistic) {
        if (!(statistic > 0)) {
            return 1;
        }
        return 2 * SpecialFunctions.normCdf(-Math.sqrt(statistic));
    }

    /** Backtesting zones of the Basel internal models approach (MAR32.8). */
    public enum Zone {
        /** No sign of a problem with the model: cumulative probability below 95 %. */
        GREEN,
        /** Questions, not conclusive: from 95 % up to 99.99 %. */
        AMBER,
        /** Almost certainly a problem: 99.99 % or more. */
        RED
    }

    /**
     * Result of the Basel traffic-light test.
     *
     * @param zone                  green, amber or red
     * @param exceptions            number of exceptions in the sample
     * @param observations          sample size
     * @param cumulativeProbability P(X &le; exceptions) for X ~ Binomial(observations, 1 - coverage)
     * @param multiplier            the backtesting-dependent multiplier of MAR32.9 Table 1
     *                              (1.50 to 2.00) for a 250-observation sample at 99 %
     *                              coverage; NaN otherwise, where the Basel text defines
     *                              the zones but no multipliers
     */
    public record TrafficLight(Zone zone, int exceptions, int observations, double cumulativeProbability,
                               double multiplier) {
    }

    // MAR32.9 Table 1 (Basel Framework): multiplier by exceptions in 250
    // observations, index 0..10 (10 = "10 or more").
    private static final double[] MAR32_MULTIPLIER = {1.50, 1.50, 1.50, 1.50, 1.50,
        1.70, 1.76, 1.83, 1.88, 1.92, 2.00};

    /**
     * Basel traffic light for 99 % VaR, the supervisory backtest of the bank-wide
     * model (Basel Framework MAR32.8-32.9 and MAR99.17, Table 2).
     *
     * <p><b>Plain idea.</b> With a correct 99 % VaR the exception count in a year
     * of 250 days is Binomial(250, 1 %), about 2.5 on average. A count you would
     * reach or exceed with a correct model at most about 5 % of the time puts
     * the model in the amber zone; about 0.01 % of the time, the red zone.</p>
     *
     * <p><b>Formal statement.</b> With {@code F} the Binomial(n, 1 - coverage)
     * CDF, the zone is GREEN if {@code F(x) < 0.95}, AMBER if
     * {@code 0.95 <= F(x) < 0.9999} and RED otherwise (MAR99.17: the amber zone
     * begins where the cumulative probability equals or exceeds 95 %, the red
     * zone where it equals or exceeds 99.99 %).</p>
     *
     * <p><b>Worked example.</b> n = 250: F(4) = 89.22 % and F(5) = 95.88 %, so
     * 0-4 exceptions are green and 5 amber; F(9) = 99.97 % and F(10) = 99.99 %,
     * so 10 or more are red. Those are the boundaries and cumulative
     * probabilities of MAR99 Table 2 (tested). Seven exceptions give AMBER with
     * multiplier 1.83.</p>
     *
     * @param exceptions   exceptions observed, 0 &le; exceptions &le; observations
     * @param observations sample size, &gt; 0 (250 for the regulatory test)
     * @return zone, cumulative probability and, for 250 observations, the multiplier
     */
    public static TrafficLight trafficLight(int exceptions, int observations) {
        return trafficLight(exceptions, observations, 0.99);
    }

    /**
     * Traffic light at another VaR coverage, with the same 95 % / 99.99 % zone
     * boundaries (the Basel text fixes 99 %; other levels are an extension for
     * internal desk monitoring). The multiplier is set only for 250
     * observations at 99 % coverage.
     *
     * @param exceptions   exceptions observed, 0 &le; exceptions &le; observations
     * @param observations sample size, &gt; 0
     * @param coverage     VaR confidence level in (0, 1), e.g. 0.99
     * @return zone and cumulative probability
     */
    public static TrafficLight trafficLight(int exceptions, int observations, double coverage) {
        if (observations <= 0 || exceptions < 0 || exceptions > observations) {
            throw new IllegalArgumentException("need 0 <= exceptions <= observations and observations > 0, got "
                    + exceptions + "/" + observations);
        }
        if (!(coverage > 0 && coverage < 1)) {
            throw new IllegalArgumentException("coverage must be in (0,1), got " + coverage);
        }
        double cdf = binomialCdf(exceptions, observations, 1 - coverage);
        Zone zone = cdf < 0.95 ? Zone.GREEN : cdf < 0.9999 ? Zone.AMBER : Zone.RED;
        double multiplier = observations == 250 && coverage == 0.99
                ? MAR32_MULTIPLIER[Math.min(exceptions, 10)] : Double.NaN;
        return new TrafficLight(zone, exceptions, observations, cdf, multiplier);
    }

    /** P(X &le; k) for X ~ Binomial(n, p), from the pmf recursion in logs. */
    static double binomialCdf(int k, int n, double p) {
        if (k >= n) {
            return 1;
        }
        double logRatio = Math.log(p) - Math.log1p(-p);
        double logPmf = n * Math.log1p(-p);
        double sum = 0;
        for (int i = 0; i <= k; i++) {
            sum += Math.exp(logPmf);
            logPmf += Math.log((double) (n - i) / (i + 1)) + logRatio;
        }
        return Math.min(1, sum);
    }
}
