package com.quantfinlib.microstructure;

/**
 * Sampling error of an exponentially weighted correlation, used to gate
 * learned signals on significance rather than on the sign of a noisy IC.
 *
 * <p>An EWMA with weight {@code α} after {@code k} observations puts weight
 * {@code w_j = α(1−α)^j} on the j-th most recent one. Its Kish (1965)
 * effective sample size is {@code (Σw)²/Σw²}:</p>
 * <pre>
 *   n_eff(k) = (1 − (1−α)^k)² · (2 − α) / (α · (1 − (1−α)^{2k}))
 * </pre>
 * <p>which tends to {@code (2 − α)/α} (about 199 at α = 0.01). Under the
 * null of zero correlation the sample correlation has standard error close
 * to {@code 1/√n_eff}. A true-zero component therefore shows a positive IC
 * half the time; gating on {@code IC > 0} admits noise, gating on
 * {@code IC/SE > z} (z = 2 by default) admits it about 2.3% of the time.</p>
 */
public final class IcSignificance {

    /** Default one-sided gate, about the 97.7th percentile of N(0,1). */
    public static final double DEFAULT_Z = 2.0;

    private IcSignificance() {
    }

    /** Kish effective sample size of an EWMA with weight {@code alpha} after {@code samples}. */
    public static double effectiveSamples(double alpha, long samples) {
        if (samples <= 0) {
            return 0;
        }
        double decay = 1 - alpha;
        double sumW = 1 - Math.pow(decay, (double) samples);
        double sumW2 = alpha * (1 - Math.pow(decay, 2.0 * samples)) / (2 - alpha);
        return sumW * sumW / sumW2;
    }

    /** Null standard error of the EWMA correlation, {@code 1/√n_eff}; +∞ before any sample. */
    public static double standardError(double alpha, long samples) {
        double n = effectiveSamples(alpha, samples);
        return n > 0 ? 1 / Math.sqrt(n) : Double.POSITIVE_INFINITY;
    }

    /** The IC shrunk toward zero by {@code z} standard errors: {@code max(0, IC − z·SE)}. */
    public static double shrunkWeight(double ic, double alpha, long samples, double z) {
        return Math.max(0, ic - z * standardError(alpha, samples));
    }
}
