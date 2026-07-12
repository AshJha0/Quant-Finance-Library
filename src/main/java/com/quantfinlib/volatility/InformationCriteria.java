package com.quantfinlib.volatility;

/**
 * AIC / BIC — the two numbers that keep model shopping honest. Every extra
 * parameter raises the maximized log-likelihood by construction; these
 * criteria charge admission for it:
 *
 * <pre>
 *   AIC = 2k - 2 ln L          (Akaike: prediction-oriented)
 *   BIC = k ln n - 2 ln L      (Schwarz: consistency-oriented)
 * </pre>
 *
 * LOWER is better for both. BIC's penalty grows with the sample size, so
 * on large samples it picks smaller models than AIC — BIC will recover the
 * true model as n grows (consistency) while AIC minimizes out-of-sample
 * prediction error even when no candidate is "true". The rule of thumb:
 * AIC for forecasting, BIC for identifying structure.
 *
 * <p>Both criteria only rank models fitted to the SAME data with likelihoods
 * on the same scale — comparing an AIC computed on returns against one
 * computed on squared returns is meaningless, and this class cannot detect
 * that for you. Made for the volatility-model zoo here ({@code Garch11} vs
 * {@code GjrGarch11} vs {@code Egarch11}: does the leverage parameter pay
 * its way?), but the arithmetic is model-agnostic. Research lane.</p>
 */
public final class InformationCriteria {

    private InformationCriteria() {
    }

    /**
     * Akaike information criterion {@code 2k - 2 ln L}.
     *
     * @param logLikelihood maximized log-likelihood ln L (finite)
     * @param parameters    number of fitted parameters k, &ge; 0
     */
    public static double aic(double logLikelihood, int parameters) {
        checkLogLik(logLikelihood);
        checkParams(parameters);
        return 2.0 * parameters - 2.0 * logLikelihood;
    }

    /**
     * Bayesian (Schwarz) information criterion {@code k ln n - 2 ln L}.
     *
     * @param logLikelihood maximized log-likelihood ln L (finite)
     * @param parameters    number of fitted parameters k, &ge; 0
     * @param observations  sample size n the likelihood was computed over, &ge; 1
     */
    public static double bic(double logLikelihood, int parameters, int observations) {
        checkLogLik(logLikelihood);
        checkParams(parameters);
        if (observations < 1) {
            throw new IllegalArgumentException("observations must be >= 1, got " + observations);
        }
        return parameters * Math.log(observations) - 2.0 * logLikelihood;
    }

    private static void checkLogLik(double logLikelihood) {
        if (!Double.isFinite(logLikelihood)) {
            throw new IllegalArgumentException("logLikelihood must be finite, got " + logLikelihood);
        }
    }

    private static void checkParams(int parameters) {
        if (parameters < 0) {
            throw new IllegalArgumentException("parameters must be >= 0, got " + parameters);
        }
    }
}
