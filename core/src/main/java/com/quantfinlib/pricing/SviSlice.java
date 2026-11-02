package com.quantfinlib.pricing;

import java.util.function.Function;

/**
 * One expiry of the raw SVI parametrisation (Gatheral 2004, "A parsimonious
 * arbitrage-free implied volatility parameterization", Global Derivatives;
 * Gatheral and Jacquier 2014, "Arbitrage-free SVI volatility surfaces",
 * Quantitative Finance 14(1), equation 3.1):
 *
 * <pre>
 *   w(k) = a + b ( ρ (k − m) + √((k − m)² + σ²) )
 * </pre>
 *
 * <p>where {@code w} is total implied variance σ_BS²·T and {@code k = ln(K/F)}
 * is log-moneyness against the forward. The parameter set is admissible
 * when b &ge; 0, |ρ| &lt; 1, σ &gt; 0 and a + bσ√(1−ρ²) &ge; 0, which keeps
 * w non-negative everywhere (Gatheral-Jacquier, section 3.1).</p>
 *
 * <p>Butterfly arbitrage. A slice is free of butterfly arbitrage if and only
 * if the density factor
 * g(k) = (1 − k w'/(2w))² − (w'²/4)(1/w + 1/4) + w''/2 is non-negative
 * (Gatheral-Jacquier 2014, lemma 2.2) and d₊ → −∞ in the right wing. The
 * Black density is g(k)/√(2πw)·exp(−d₋²/2); {@link #minDensityFactor}
 * checks g on a grid.</p>
 *
 * <p>Calibration ({@link #calibrate}) is Levenberg-Marquardt on weighted
 * total-variance residuals in a reparametrisation that keeps every
 * admissibility bound and Lee's (2004) wing bound b(1+|ρ|) &le; 4, plus an
 * optional penalty residual on negative g over a grid that extends half a
 * unit of log-moneyness past the quotes. Weights are the caller's: vega
 * weights or the reciprocal of the bid-ask spread in variance terms are the
 * usual choices. The fit is multi-start (27 starts) because the SVI
 * objective has several local minima.</p>
 *
 * @param a     variance level
 * @param b     wing slope scale, &ge; 0
 * @param rho   rotation, in (−1, 1)
 * @param m     horizontal shift (log-moneyness)
 * @param sigma ATM curvature, &gt; 0
 */
public record SviSlice(double a, double b, double rho, double m, double sigma) {

    /** Weight of the min(g, 0) penalty residuals in {@link #calibrate}. */
    public static final double BUTTERFLY_PENALTY = 100;

    public SviSlice {
        if (!Double.isFinite(a) || !Double.isFinite(m) || !(b >= 0) || !Double.isFinite(b)
                || !(Math.abs(rho) < 1) || !(sigma > 0) || !Double.isFinite(sigma)) {
            throw new IllegalArgumentException("SVI requires finite a, m; b >= 0; |rho| < 1; sigma > 0");
        }
        if (a + b * sigma * Math.sqrt(1 - rho * rho) < -1e-14) {
            throw new IllegalArgumentException("a + b sigma sqrt(1 - rho^2) must be >= 0 (negative variance)");
        }
    }

    /** Total implied variance at log-moneyness {@code k}. */
    public double totalVariance(double k) {
        double u = k - m;
        return a + b * (rho * u + Math.sqrt(u * u + sigma * sigma));
    }

    /** First derivative dw/dk. */
    public double slope(double k) {
        double u = k - m;
        return b * (rho + u / Math.sqrt(u * u + sigma * sigma));
    }

    /** Second derivative d²w/dk². */
    public double curvature(double k) {
        double u = k - m;
        double s = Math.sqrt(u * u + sigma * sigma);
        return b * sigma * sigma / (s * s * s);
    }

    /** Implied volatility at log-moneyness {@code k} for expiry {@code t} years. */
    public double impliedVol(double k, double t) {
        if (!(t > 0)) {
            throw new IllegalArgumentException("t must be > 0");
        }
        return Math.sqrt(Math.max(totalVariance(k), 0) / t);
    }

    /** Gatheral-Jacquier density factor g(k); negative means butterfly arbitrage. */
    public double densityFactor(double k) {
        return densityFactor(k, totalVariance(k), slope(k), curvature(k));
    }

    /** Minimum of g over {@code n} equally spaced points of [kMin, kMax]. */
    public double minDensityFactor(double kMin, double kMax, int n) {
        if (n < 2 || !(kMax > kMin)) {
            throw new IllegalArgumentException("need n >= 2 and kMax > kMin");
        }
        double min = Double.POSITIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            min = Math.min(min, densityFactor(kMin + (kMax - kMin) * i / (n - 1)));
        }
        return min;
    }

    /**
     * g(k) from w, w', w'' (Gatheral-Jacquier 2014, equation 2.1); negative
     * infinity where w is not positive.
     */
    static double densityFactor(double k, double w, double w1, double w2) {
        if (!(w > 0)) {
            return Double.NEGATIVE_INFINITY;
        }
        double x = 1 - k * w1 / (2 * w);
        return x * x - 0.25 * w1 * w1 * (1 / w + 0.25) + 0.5 * w2;
    }

    /** Root-mean-square total-variance error against quotes (unweighted). */
    public double rmse(double[] k, double[] w) {
        if (k.length != w.length || k.length == 0) {
            throw new IllegalArgumentException("k and w must be non-empty and the same length");
        }
        double s = 0;
        for (int i = 0; i < k.length; i++) {
            double e = totalVariance(k[i]) - w[i];
            s += e * e;
        }
        return Math.sqrt(s / k.length);
    }

    /**
     * Calibrates a slice to total-variance quotes with the butterfly penalty on.
     *
     * @param k       log-moneyness ln(K/F) of each quote
     * @param w       market total variance σ²T of each quote
     * @param weights residual weights, or null for equal weights
     */
    public static SviSlice calibrate(double[] k, double[] w, double[] weights) {
        return calibrate(k, w, weights, true);
    }

    /**
     * Calibrates a slice; {@code butterflyPenalty} adds residuals
     * {@link #BUTTERFLY_PENALTY}·min(g, 0) on a 121-point grid over [min k − 0.5, max k + 0.5].
     * At least five quotes are required.
     */
    public static SviSlice calibrate(double[] k, double[] w, double[] weights, boolean butterflyPenalty) {
        int n = k.length;
        if (n < 5 || w.length != n || (weights != null && weights.length != n)) {
            throw new IllegalArgumentException("need at least 5 quotes with matching lengths");
        }
        double kMin = Double.POSITIVE_INFINITY;
        double kMax = Double.NEGATIVE_INFINITY;
        double wMin = Double.POSITIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            if (!Double.isFinite(k[i]) || !(w[i] > 0) || !Double.isFinite(w[i])
                    || (weights != null && !(weights[i] >= 0 && Double.isFinite(weights[i])))) {
                throw new IllegalArgumentException("quote " + i + " invalid");
            }
            kMin = Math.min(kMin, k[i]);
            kMax = Math.max(kMax, k[i]);
            wMin = Math.min(wMin, w[i]);
        }
        double[] wt = new double[n];
        for (int i = 0; i < n; i++) {
            wt[i] = weights == null ? 1 : weights[i];
        }
        double[] kq = k.clone();
        double[] wq = w.clone();
        double[] grid = new double[butterflyPenalty ? 121 : 0];
        for (int i = 0; i < grid.length; i++) {
            grid[i] = kMin - 0.5 + (kMax - kMin + 1) * i / (grid.length - 1);
        }
        final double lo = kMin - 1;
        final double hi = kMax + 1;
        Function<double[], SviSlice> decode = p -> {
            double rho = 0.999 * Math.tanh(p[2]);
            double b = 4 / (1 + Math.abs(rho)) / (1 + Math.exp(-p[1]));
            double sig = 1e-4 + Math.exp(p[4]);
            double m = 0.5 * (lo + hi) + 0.5 * (hi - lo) * Math.tanh(p[3]);
            double a = -b * sig * Math.sqrt(1 - rho * rho) + Math.exp(p[0]);
            return new SviSlice(a, b, rho, m, sig);
        };
        Function<double[], double[]> res = p -> {
            SviSlice s = decode.apply(p);
            double[] r = new double[n + grid.length];
            for (int i = 0; i < n; i++) {
                r[i] = wt[i] * (s.totalVariance(kq[i]) - wq[i]);
            }
            for (int i = 0; i < grid.length; i++) {
                r[n + i] = BUTTERFLY_PENALTY * Math.min(s.densityFactor(grid[i]), 0);
            }
            return r;
        };
        LeastSquares.Solution best = null;
        double scale = Math.max(Math.abs(kMin), Math.abs(kMax));
        for (double rho0 : new double[]{-0.5, 0, 0.5}) {
            for (double sig0 : new double[]{0.05, 0.2, 0.6}) {
                for (double mFrac : new double[]{-0.5, 0, 0.5}) {
                    double m0 = mFrac * scale;
                    double[] start = {
                            Math.log(0.8 * wMin),
                            -2,
                            atanh(rho0 / 0.999),
                            atanh((m0 - 0.5 * (lo + hi)) / (0.5 * (hi - lo))),
                            Math.log(sig0)};
                    LeastSquares.Solution s = LeastSquares.minimise(res, start, 400);
                    if (best == null || s.cost() < best.cost()) {
                        best = s;
                    }
                }
            }
        }
        double[] p = new double[5];
        for (int i = 0; i < 5; i++) {
            p[i] = best.x(i);
        }
        return decode.apply(p);
    }

    private static double atanh(double x) {
        return 0.5 * Math.log((1 + x) / (1 - x));
    }
}
