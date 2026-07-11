package com.quantfinlib.backtest.validation;

import com.quantfinlib.util.MathUtils;

/**
 * Sharpe ratio significance tests (Bailey &amp; López de Prado):
 *
 * <ul>
 *   <li><b>Probabilistic Sharpe Ratio</b> — the probability the true Sharpe
 *       exceeds a benchmark, adjusting for track length and non-normal
 *       returns (skew, kurtosis).</li>
 *   <li><b>Deflated Sharpe Ratio</b> — PSR against the Sharpe you'd expect
 *       from the <i>best of N</i> random trials: the multiple-testing haircut
 *       for a strategy picked from a parameter grid.</li>
 * </ul>
 *
 * <p>All Sharpe inputs are per-period (not annualized) with observation
 * count {@code nObs}.</p>
 */
public final class SharpeValidation {

    private static final double EULER_GAMMA = 0.5772156649015329;

    private SharpeValidation() {
    }

    /** Probability the true Sharpe exceeds {@code benchmarkSharpe}, in [0,1]. */
    public static double probabilisticSharpe(double observedSharpe, double benchmarkSharpe,
                                             int nObs, double skewness, double kurtosis) {
        if (nObs < 2) {
            throw new IllegalArgumentException("need at least 2 observations");
        }
        double variance = 1 - skewness * observedSharpe
                + (kurtosis - 1) / 4.0 * observedSharpe * observedSharpe;
        if (variance <= 0) {
            return observedSharpe > benchmarkSharpe ? 1 : 0;
        }
        double z = (observedSharpe - benchmarkSharpe) * Math.sqrt(nObs - 1) / Math.sqrt(variance);
        return MathUtils.normCdf(z);
    }

    /**
     * Expected maximum Sharpe among {@code trials} independent zero-skill
     * strategies whose Sharpe estimates have the given cross-trial variance.
     */
    public static double expectedMaxSharpe(int trials, double varianceOfTrialSharpes) {
        if (trials < 2) {
            throw new IllegalArgumentException("need at least 2 trials");
        }
        double sd = Math.sqrt(Math.max(0, varianceOfTrialSharpes));
        return sd * ((1 - EULER_GAMMA) * MathUtils.normInv(1 - 1.0 / trials)
                + EULER_GAMMA * MathUtils.normInv(1 - 1.0 / (trials * Math.E)));
    }

    /**
     * Deflated Sharpe: PSR of the winner against the expected-max benchmark
     * implied by all the parameter combinations that were tried. Values near
     * 1 mean the edge survives the multiple-testing haircut; below ~0.95 the
     * "discovery" is likely selection bias.
     *
     * @param trialSharpes per-period Sharpe of every trial in the search
     *                     (including the winner)
     */
    public static double deflatedSharpe(double observedSharpe, double[] trialSharpes,
                                        int nObs, double skewness, double kurtosis) {
        double benchmark = expectedMaxSharpe(trialSharpes.length, MathUtils.variance(trialSharpes));
        return probabilisticSharpe(observedSharpe, benchmark, nObs, skewness, kurtosis);
    }

    /**
     * Minimum track record length (Bailey &amp; López de Prado): how many
     * periods of THIS performance are needed before
     * {@link #probabilisticSharpe} would clear {@code confidence} that
     * the true Sharpe exceeds the benchmark — the allocator's question
     * ("how long until this manager's record means something?") in
     * closed form:
     *
     * <pre>  n* = 1 + (1 − γ₃·SR + (γ₄−1)/4·SR²) · (z_conf / (SR − SR*))²</pre>
     *
     * Returns {@code POSITIVE_INFINITY} when the observed Sharpe does
     * not exceed the benchmark — no track record length proves an edge
     * the record does not show. Sharpe inputs are PER-PERIOD (not
     * annualized), matching {@code probabilisticSharpe}.
     */
    public static double minTrackRecordLength(double observedSharpe, double benchmarkSharpe,
                                              double skewness, double kurtosis,
                                              double confidence) {
        if (!(confidence > 0 && confidence < 1)) {
            throw new IllegalArgumentException("confidence must be in (0, 1)");
        }
        if (!Double.isFinite(observedSharpe) || !Double.isFinite(benchmarkSharpe)
                || !Double.isFinite(skewness) || !Double.isFinite(kurtosis)) {
            throw new IllegalArgumentException("inputs must be finite");
        }
        if (observedSharpe <= benchmarkSharpe) {
            return Double.POSITIVE_INFINITY;
        }
        double variance = 1 - skewness * observedSharpe
                + (kurtosis - 1) / 4.0 * observedSharpe * observedSharpe;
        if (variance <= 0) {
            return 2;                  // PSR is already 1: any record suffices
        }
        double z = MathUtils.normInv(confidence);
        double edge = observedSharpe - benchmarkSharpe;
        return 1 + variance * z * z / (edge * edge);
    }
}
