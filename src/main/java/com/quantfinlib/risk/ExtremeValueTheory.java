package com.quantfinlib.risk;

/**
 * Extreme value theory via peaks-over-threshold — the statistically
 * principled way to ask about quantiles beyond the sample. Historical VaR
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
 * returning a finite lie). {@link #fitPot} uses probability-weighted
 * moments (Hosking and Wallis 1987): closed form, good for ξ &lt; 0.5 but
 * biased and inefficient beyond. {@link #fitPotMle} maximises the GPD
 * likelihood through Grimshaw's (1993, Technometrics 35, 185-191)
 * one-dimensional profile in θ = ξ/β and reports the asymptotic standard
 * errors of Smith (1987, Ann. Statist. 15, 1174-1207), valid for
 * ξ &gt; −0.5. {@link #meanExcess} gives the mean-excess function used to
 * choose the threshold (linear in u above a valid threshold).
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

    /**
     * A maximum-likelihood GPD fit with asymptotic standard errors
     * {@code Var ξ̂ = (1+ξ)²/m}, {@code Var β̂ = 2β²(1+ξ)/m} (Smith 1987).
     */
    public record GpdMle(GpdFit fit, double shapeStdError, double scaleStdError,
                         double logLikelihood) {
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

    /**
     * Maximum-likelihood GPD fit to the exceedances over the
     * {@code thresholdQuantile} of the sample. The log-likelihood of
     * exceedances y is {@code −m·ln β − (1 + 1/ξ)·Σ ln(1 + ξ·y/β)}; with
     * θ = ξ/β the maximising ξ for fixed θ is {@code (1/m)·Σ ln(1 + θ·y)} and
     * the profile {@code −m·ln(ξ(θ)/θ) − m·(1 + ξ(θ))} is maximised over
     * θ &gt; −1/max(y) by a grid scan followed by golden-section search.
     */
    public static GpdMle fitPotMle(double[] losses, double thresholdQuantile) {
        GpdFit pwm = fitPot(losses, thresholdQuantile);
        double u = pwm.threshold();
        double[] sorted = losses.clone();
        java.util.Arrays.sort(sorted);
        int m = pwm.exceedances();
        double[] y = new double[m];
        for (int i = 0; i < m; i++) {
            y[i] = sorted[sorted.length - m + i] - u;
        }
        double yMax = y[m - 1];
        double yMean = 0;
        for (double v : y) {
            yMean += v;
        }
        yMean /= m;
        // Grid in θ·ȳ from just above −ȳ/yMax to 200 (ξ up to ~5).
        double lo = -(1 - 1e-6) / yMax;
        double hi = 200 / yMean;
        int grid = 2000;
        double bestT = 0;
        double bestL = exponentialLogLik(y, yMean);
        double[] ts = new double[grid + 1];
        for (int g = 0; g <= grid; g++) {
            double f = (double) g / grid;
            // Denser near zero: map f to θ with a cubic stretch on each side.
            double t = f < 0.5 ? lo * Math.pow(1 - 2 * f, 3) : hi * Math.pow(2 * f - 1, 3);
            ts[g] = t;
            double l = profile(y, t, yMean);
            if (l > bestL) {
                bestL = l;
                bestT = t;
            }
        }
        // Golden-section on the bracket around the best grid point.
        int k = 0;
        double best = Double.NEGATIVE_INFINITY;
        for (int g = 0; g <= grid; g++) {
            double l = profile(y, ts[g], yMean);
            if (l > best) {
                best = l;
                k = g;
            }
        }
        double a = ts[Math.max(0, k - 1)];
        double b = ts[Math.min(grid, k + 1)];
        double gr = (Math.sqrt(5) - 1) / 2;
        double c = b - gr * (b - a);
        double d = a + gr * (b - a);
        double fc = profile(y, c, yMean);
        double fd = profile(y, d, yMean);
        for (int it = 0; it < 200 && Math.abs(b - a) > 1e-14 * Math.max(Math.abs(a) + Math.abs(b), 1e-300); it++) {
            if (fc > fd) {
                b = d;
                d = c;
                fd = fc;
                c = b - gr * (b - a);
                fc = profile(y, c, yMean);
            } else {
                a = c;
                c = d;
                fc = fd;
                d = a + gr * (b - a);
                fd = profile(y, d, yMean);
            }
        }
        double tStar = fc > fd ? c : d;
        double lStar = Math.max(fc, fd);
        if (bestL > lStar) {
            tStar = bestT;
            lStar = bestL;
        }
        double xi;
        double beta;
        if (Math.abs(tStar) < 1e-12 / yMean) {
            xi = 0;
            beta = yMean;
        } else {
            xi = 0;
            for (double v : y) {
                xi += Math.log1p(tStar * v);
            }
            xi /= m;
            beta = xi / tStar;
        }
        if (!(beta > 0) || !Double.isFinite(xi)) {
            throw new IllegalStateException("GPD likelihood has no interior maximum");
        }
        double seXi = xi > -0.5 ? (1 + xi) / Math.sqrt(m) : Double.NaN;
        double seBeta = xi > -0.5 ? beta * Math.sqrt(2 * (1 + xi) / m) : Double.NaN;
        return new GpdMle(new GpdFit(u, xi, beta, m, pwm.sampleSize()), seXi, seBeta, lStar);
    }

    /** GPD log-likelihood of exceedances {@code y} at (ξ, β); −∞ outside the support. */
    public static double gpdLogLikelihood(double[] y, double shape, double scale) {
        if (!(scale > 0)) {
            return Double.NEGATIVE_INFINITY;
        }
        double ll = -y.length * Math.log(scale);
        for (double v : y) {
            double z = 1 + shape * v / scale;
            if (!(z > 0)) {
                return Double.NEGATIVE_INFINITY;
            }
            ll -= Math.abs(shape) < 1e-12 ? v / scale : (1 + 1 / shape) * Math.log(z);
        }
        return ll;
    }

    private static double profile(double[] y, double theta, double yMean) {
        if (Math.abs(theta) < 1e-12 / yMean) {
            return exponentialLogLik(y, yMean);
        }
        double xi = 0;
        for (double v : y) {
            double z = 1 + theta * v;
            if (!(z > 0)) {
                return Double.NEGATIVE_INFINITY;
            }
            xi += Math.log(z);
        }
        xi /= y.length;
        double beta = xi / theta;
        if (!(beta > 0)) {
            return Double.NEGATIVE_INFINITY;
        }
        return -y.length * Math.log(beta) - y.length * (1 + xi);
    }

    private static double exponentialLogLik(double[] y, double mean) {
        return -y.length * Math.log(mean) - y.length;
    }

    /**
     * Empirical mean-excess function {@code e(u) = mean(L − u | L > u)} at
     * each threshold (NaN where no loss exceeds u). For a GPD tail it is
     * linear in u with slope ξ/(1−ξ); the threshold should be chosen where
     * the plot becomes linear.
     */
    public static double[] meanExcess(double[] losses, double[] thresholds) {
        double[] out = new double[thresholds.length];
        for (int k = 0; k < thresholds.length; k++) {
            double sum = 0;
            int c = 0;
            for (double l : losses) {
                if (l > thresholds[k]) {
                    sum += l - thresholds[k];
                    c++;
                }
            }
            out[k] = c == 0 ? Double.NaN : sum / c;
        }
        return out;
    }
}
