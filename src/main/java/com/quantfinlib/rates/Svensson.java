package com.quantfinlib.rates;

import com.quantfinlib.util.MathUtils;

/**
 * SVENSSON (Nelson-Siegel-Svensson) yield-curve fit — {@link NelsonSiegel}
 * with a SECOND curvature hump, the form most central banks actually
 * publish (the ECB's daily curve is exactly this):
 *
 * <pre>
 *   z(t) = b0 + b1 * f1(t/l1) + b2 * f2(t/l1) + b3 * f2(t/l2)
 *   f1(x) = (1 - e^-x)/x        f2(x) = (1 - e^-x)/x - e^-x
 * </pre>
 *
 * <ul>
 *   <li><b>b0</b> — the LEVEL: z(infinity);</li>
 *   <li><b>b1</b> — the SLOPE: z(0) = b0 + b1 (b1 &gt; 0 is inversion);</li>
 *   <li><b>b2, lambda1</b> — the FIRST hump and where it sits;</li>
 *   <li><b>b3, lambda2</b> — the SECOND hump: the long-end flex a single
 *       hump cannot bend into — real curves routinely show a short-end
 *       bump (policy expectations) AND a 10y+ dip (convexity demand), and
 *       plain Nelson-Siegel must split the difference.</li>
 * </ul>
 *
 * <p>Fitting mirrors {@link NelsonSiegel} exactly: for FIXED (l1, l2) the
 * model is LINEAR in (b0, b1, b2, b3) — an exact 4-regressor OLS solve —
 * so the fit is a 2-D log-spaced grid over the lambdas with an OLS solve
 * per node, deterministic and free of local-minimum roulette. Nodes with
 * {@code lambda2 <= lambda1} are skipped: the two f2 regressors collide as
 * the lambdas meet (exact collinearity at equality), and the ordering
 * makes the parameterization identifiable — hump one is always the
 * shorter-dated one. Betas are NOT sign-constrained: an inverted or
 * double-dipped curve is data, not an error.</p>
 *
 * <p>With b3 = 0 the model IS Nelson-Siegel, so with the lambdas free
 * Svensson can always match NS in-sample; the two FITTERS search different
 * lambda grids (NS: 80 nodes 1-D; here: 50 nodes 2-D with lambda1 &lt; 10),
 * so on data whose best single lambda falls between this grid's nodes NS
 * can win by a grid-granularity sliver (rmse differences at the 1e-8
 * level, tested to agree within tolerance). The price of the extra hump is
 * two more parameters — on sparse or single-hump curves prefer NS and let
 * {@code InformationCriteria} referee. Research lane.</p>
 */
public final class Svensson {

    /** Fitted parameters plus the fit's root-mean-square error. */
    public record Fit(double b0, double b1, double b2, double b3,
                      double lambda1, double lambda2, double rmse) {

        /** Model zero rate at tenor {@code t} years, &gt; 0. */
        public double zeroRate(double t) {
            if (!(t > 0) || t == Double.POSITIVE_INFINITY) {
                throw new IllegalArgumentException("tenor must be positive and finite, got " + t);
            }
            double x1 = t / lambda1;
            double x2 = t / lambda2;
            double f1 = (1 - Math.exp(-x1)) / x1;
            double g1 = f1 - Math.exp(-x1);
            double g2 = (1 - Math.exp(-x2)) / x2 - Math.exp(-x2);
            return b0 + b1 * f1 + b2 * g1 + b3 * g2;
        }

        /** The short-rate limit z(0+) = b0 + b1 (exact in the model). */
        public double shortRate() {
            return b0 + b1;
        }

        /** The long-end asymptote z(infinity) = b0. */
        public double longRate() {
            return b0;
        }
    }

    private Svensson() {
    }

    /**
     * Fits by 2-D log-spaced lambda grid (0.1y-10y, 50 nodes per axis,
     * {@code lambda2 > lambda1} only) + exact 4-regressor OLS per node.
     *
     * @param tenorYears observation tenors, &ge; 6 distinct, all &gt; 0
     * @param zeroRates  observed zero rates (continuously compounded)
     */
    public static Fit fit(double[] tenorYears, double[] zeroRates) {
        int n = tenorYears.length;
        if (n < 6 || zeroRates.length != n) {
            throw new IllegalArgumentException(
                    "need >= 6 aligned tenor/rate observations, got " + n + "/" + zeroRates.length);
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
        int nodes = 50;
        double lo = Math.log(0.1), hi = Math.log(10.0);
        for (int g1 = 0; g1 < nodes; g1++) {
            double lambda1 = Math.exp(lo + (hi - lo) * g1 / (nodes - 1));
            for (int g2 = g1 + 1; g2 < nodes; g2++) {
                double lambda2 = Math.exp(lo + (hi - lo) * g2 / (nodes - 1));
                // OLS in (b0,b1,b2,b3) via 4x4 normal equations.
                double[][] xtx = new double[4][4];
                double[] xty = new double[4];
                for (int i = 0; i < n; i++) {
                    double x1 = tenorYears[i] / lambda1;
                    double x2 = tenorYears[i] / lambda2;
                    double f1 = (1 - Math.exp(-x1)) / x1;
                    double[] row = {1, f1, f1 - Math.exp(-x1),
                            (1 - Math.exp(-x2)) / x2 - Math.exp(-x2)};
                    for (int a = 0; a < 4; a++) {
                        xty[a] += row[a] * zeroRates[i];
                        for (int b = 0; b < 4; b++) {
                            xtx[a][b] += row[a] * row[b];
                        }
                    }
                }
                double[] beta;
                try {
                    beta = MathUtils.solveLinear(xtx, xty);
                } catch (IllegalArgumentException singular) {
                    continue; // degenerate node (near-collinear regressors); skip
                }
                Fit candidate = new Fit(beta[0], beta[1], beta[2], beta[3], lambda1, lambda2, 0);
                double sse = 0;
                for (int i = 0; i < n; i++) {
                    double e = candidate.zeroRate(tenorYears[i]) - zeroRates[i];
                    sse += e * e;
                }
                if (sse < bestSse) {
                    bestSse = sse;
                    best = new Fit(beta[0], beta[1], beta[2], beta[3], lambda1, lambda2,
                            Math.sqrt(sse / n));
                }
            }
        }
        if (best == null) {
            throw new IllegalArgumentException("no lambda node produced a solvable fit");
        }
        return best;
    }
}
