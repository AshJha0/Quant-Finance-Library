package com.quantfinlib.risk;

/**
 * Extreme value theory via peaks-over-threshold — the statistically
 * honest way to ask about quantiles BEYOND the sample. Historical VaR
 * at 99.9% from 500 observations is reading the worst half-observation;
 * EVT instead fits the Generalized Pareto Distribution to the
 * exceedances over a high threshold (the Pickands-Balkema-de Haan
 * theorem says the tail of ANY well-behaved distribution converges to a
 * GPD), then extrapolates along the fitted tail:
 *
 * <pre>  VaR_p = u + (β/ξ)·[((n/Nᵤ)(1−p))^{−ξ} − 1]</pre>
 *
 * <p>The shape ξ is the number to stare at: ξ ≈ 0 is an exponential
 * tail (Gaussian-ish), ξ &gt; 0 is a power-law tail (fat — equity
 * returns typically fit ξ ≈ 0.2-0.4), and ξ ≥ 1 means the tail mean
 * does not even exist ({@code expectedShortfall} refuses rather than
 * returning a finite lie). Fitting uses probability-weighted moments —
 * closed-form, no optimizer, well-behaved for ξ &lt; 0.5 (documented
 * range; MLE's edge beyond that is not worth an optimizer dependency).
 * The threshold choice is the caller's judgment call — the classic
 * diagnostic is fitting at several thresholds and checking ξ stability;
 * a quantile between 0.90 and 0.95 of the losses is the usual start.
 * Research lane, deterministic.</p>
 */
public final class ExtremeValueTheory {

    /**
     * A fitted POT tail model.
     *
     * @param threshold   u — losses above this were fitted
     * @param shape       ξ — the tail index (fat when positive)
     * @param scale       β — the GPD scale
     * @param exceedances how many losses exceeded u
     * @param sampleSize  total losses the tail sits in
     */
    public record GpdFit(double threshold, double shape, double scale,
                         int exceedances, int sampleSize) {

        /** Tail VaR at one-sided confidence {@code p} (must lie in the tail). */
        public double var(double p) {
            double tailProb = (double) exceedances / sampleSize;
            if (!(p > 1 - tailProb) || !(p < 1)) {
                throw new IllegalArgumentException("p = " + p
                        + " must lie in (" + (1 - tailProb) + ", 1) — below that "
                        + "use plain historical VaR; 99.9% is 0.999, not 99.9");
            }
            double ratio = (1 - p) / tailProb;
            if (Math.abs(shape) < 1e-9) {
                return threshold - scale * Math.log(ratio);
            }
            return threshold + scale / shape * (Math.pow(ratio, -shape) - 1);
        }

        /**
         * Tail expected shortfall at {@code p}. Refuses (throws) when
         * ξ ≥ 1: the fitted tail's mean is infinite, and a finite number
         * here would be the exact lie EVT exists to prevent.
         */
        public double expectedShortfall(double p) {
            if (shape >= 1) {
                throw new IllegalStateException("shape " + shape
                        + " >= 1: the fitted tail has no finite mean");
            }
            double v = var(p);
            return (v + scale - shape * threshold) / (1 - shape);
        }
    }

    private ExtremeValueTheory() {
    }

    /**
     * Fits a GPD to the losses exceeding the {@code thresholdQuantile}
     * of the sample (e.g. 0.90), via probability-weighted moments.
     * Losses are positive numbers (feed {@code -returns} or a loss
     * series directly).
     */
    public static GpdFit fitPot(double[] losses, double thresholdQuantile) {
        if (losses.length < 50) {
            throw new IllegalArgumentException("need >= 50 losses for a tail fit");
        }
        if (!(thresholdQuantile >= 0.5 && thresholdQuantile < 1)) {
            throw new IllegalArgumentException("thresholdQuantile must be in [0.5, 1)");
        }
        double[] sorted = losses.clone();
        java.util.Arrays.sort(sorted);
        int n = sorted.length;
        // NaN/Infinity sort into the tail — exactly where they would
        // poison the PWMs with a misleading "degenerate tail" message.
        if (!Double.isFinite(sorted[0]) || !Double.isFinite(sorted[n - 1])) {
            throw new IllegalArgumentException("losses must be finite");
        }
        int thresholdIndex = (int) Math.floor(thresholdQuantile * n) - 1;
        double u = sorted[Math.max(0, thresholdIndex)];

        // Exceedances y_i = loss − u, ascending — STRICTLY above u: on
        // discretized data (P&L snapped to ticks) ties equal to u would
        // otherwise enter as y = 0 exceedances, deflating both PWMs and
        // biasing the shape estimate.
        int start = thresholdIndex + 1;
        while (start < n && sorted[start] <= u) {
            start++;
        }
        int m = n - start;
        if (m < 10) {
            throw new IllegalArgumentException(
                    "only " + m + " exceedances — lower the threshold or bring more data");
        }
        // Probability-weighted moments (Hosking & Wallis 1987):
        // b0 = E[Y] = mean(y);  a1 = E[Y·(1−F(Y))], estimated over the
        // ASCENDING order statistics with weight (m−1−i)/(m−1). Then
        // ξ = 2 − b0/(b0 − 2a1),  β = 2·b0·a1/(b0 − 2a1) — for GPD(ξ, β):
        // b0 = β/(1−ξ), a1 = β/(2(2−ξ)), and the algebra inverts exactly.
        double b0 = 0;
        double a1 = 0;
        for (int i = 0; i < m; i++) {
            double y = sorted[start + i] - u;
            b0 += y;
            a1 += y * (m - 1 - i) / (double) (m - 1);
        }
        b0 /= m;
        a1 /= m;
        double denom = b0 - 2 * a1;
        double shape = 2 - b0 / denom;
        double scale = 2 * b0 * a1 / denom;
        if (!(scale > 0) || !Double.isFinite(shape)) {
            throw new IllegalArgumentException(
                    "degenerate tail (all exceedances equal?) — PWM fit failed");
        }
        return new GpdFit(u, shape, scale, m, n);
    }
}
