package com.quantfinlib.rates;

import com.quantfinlib.util.MathUtils;

/**
 * NELSON-SIEGEL yield-curve fit — the parametric answer to "what SHAPE is
 * the curve", where {@link YieldCurve} is the exact-repricing answer to
 * "what IS the curve". Central banks (ECB, Fed) publish curves in exactly
 * this form because four numbers carry the whole story:
 *
 * <pre>  z(t) = b0 + b1 · (1−e^{−t/λ})/(t/λ) + b2 · [(1−e^{−t/λ})/(t/λ) − e^{−t/λ}]</pre>
 *
 * <ul>
 *   <li><b>b0</b> — the LEVEL: z(∞), where the long end settles;</li>
 *   <li><b>b1</b> — the SLOPE: z(0) = b0 + b1, so b1 &lt; 0 is an upward
 *       curve and b1 &gt; 0 is INVERSION — the recession-signal number;</li>
 *   <li><b>b2</b> — the CURVATURE: the mid-curve hump, peaking near
 *       t ≈ λ;</li>
 *   <li><b>λ</b> — WHERE the hump sits (years).</li>
 * </ul>
 *
 * <p>Fitting exploits the model's one great convenience: for FIXED λ the
 * model is LINEAR in (b0, b1, b2) — an exact 3×3 least-squares solve. So
 * the fit is a log-spaced grid search over λ with an OLS solve at each
 * node, keeping the whole thing deterministic and free of local-minimum
 * roulette (the classic failure of fitting all four jointly). Betas are
 * NOT constrained to "sensible" signs: an inverted curve is data, not an
 * error.</p>
 *
 * <p>Use the parametric fit for smoothing noisy quotes, cross-country
 * comparison, and factor analysis (level/slope/curvature ARE the first
 * three PCA factors of yield curves, to good approximation); use the
 * bootstrap when you need every input repriced exactly. Research lane.</p>
 */
public final class NelsonSiegel {

    /** Fitted parameters plus the fit's root-mean-square error. */
    public record Fit(double b0, double b1, double b2, double lambda, double rmse) {

        /** Model zero rate at tenor {@code t} years, &gt; 0. */
        public double zeroRate(double t) {
            if (!(t > 0) || t == Double.POSITIVE_INFINITY) {
                throw new IllegalArgumentException("tenor must be positive and finite, got " + t);
            }
            double x = t / lambda;
            double slopeLoading = (1 - Math.exp(-x)) / x;
            return b0 + b1 * slopeLoading + b2 * (slopeLoading - Math.exp(-x));
        }

        /** The short-rate limit z(0+) = b0 + b1 (exact in the model). */
        public double shortRate() {
            return b0 + b1;
        }

        /** The long-end asymptote z(∞) = b0. */
        public double longRate() {
            return b0;
        }
    }

    private NelsonSiegel() {
    }

    /**
     * Fits by log-spaced λ grid (0.1y–10y, 80 nodes) + exact OLS per node.
     *
     * @param tenorYears observation tenors, &ge; 4 distinct, all &gt; 0
     * @param zeroRates  observed zero rates (continuously compounded)
     */
    public static Fit fit(double[] tenorYears, double[] zeroRates) {
        int n = tenorYears.length;
        if (n < 4 || zeroRates.length != n) {
            throw new IllegalArgumentException(
                    "need >= 4 aligned tenor/rate observations, got " + n + "/" + zeroRates.length);
        }
        for (int i = 0; i < n; i++) {
            if (!(tenorYears[i] > 0) || tenorYears[i] == Double.POSITIVE_INFINITY) {
                throw new IllegalArgumentException("tenor must be positive and finite: " + tenorYears[i]);
            }
            if (!Double.isFinite(zeroRates[i])) {
                throw new IllegalArgumentException("zero rate must be finite: " + zeroRates[i]);
            }
        }
        double bestSse = Double.POSITIVE_INFINITY;
        Fit best = null;
        int nodes = 80;
        double lo = Math.log(0.1), hi = Math.log(10.0);
        for (int g = 0; g < nodes; g++) {
            double lambda = Math.exp(lo + (hi - lo) * g / (nodes - 1));
            // OLS in (b0,b1,b2) via 3x3 normal equations.
            double[][] xtx = new double[3][3];
            double[] xty = new double[3];
            for (int i = 0; i < n; i++) {
                double x = tenorYears[i] / lambda;
                double f1 = (1 - Math.exp(-x)) / x;
                double[] row = {1, f1, f1 - Math.exp(-x)};
                for (int a = 0; a < 3; a++) {
                    xty[a] += row[a] * zeroRates[i];
                    for (int b = 0; b < 3; b++) {
                        xtx[a][b] += row[a] * row[b];
                    }
                }
            }
            double[] beta;
            try {
                beta = MathUtils.solveLinear(xtx, xty);
            } catch (IllegalArgumentException singular) {
                continue; // degenerate node (e.g. all tenors << lambda); skip
            }
            double sse = 0;
            Fit candidate = new Fit(beta[0], beta[1], beta[2], lambda, 0);
            for (int i = 0; i < n; i++) {
                double e = candidate.zeroRate(tenorYears[i]) - zeroRates[i];
                sse += e * e;
            }
            if (sse < bestSse) {
                bestSse = sse;
                best = new Fit(beta[0], beta[1], beta[2], lambda, Math.sqrt(sse / n));
            }
        }
        if (best == null) {
            throw new IllegalArgumentException("no lambda node produced a solvable fit");
        }
        return best;
    }
}
