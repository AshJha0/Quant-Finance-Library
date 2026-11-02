package com.quantfinlib.pricing;

import com.quantfinlib.pricing.BlackScholes.OptionType;

/**
 * Calibrates the five {@link Heston.Params} to a grid of Black-Scholes implied
 * volatilities by Levenberg-Marquardt (Levenberg 1944; Marquardt 1963, with
 * Marquardt's diagonal scaling).
 *
 * <p>The objective is the vega-weighted price error, a first-order proxy for
 * the implied-volatility error that avoids an implied-vol inversion per
 * iteration:</p>
 *
 * <pre>
 *   r_i = (C_Heston(K_i, T_i; p) - C_BS(K_i, T_i; sigma_i)) / vega_BS(K_i, T_i; sigma_i)
 *   minimise  sum_i r_i^2 / 2
 * </pre>
 *
 * <p>Each quote is priced as the out-of-the-money option (put below the
 * forward, call at or above), where the COS expansion is most accurate. The
 * Jacobian is the analytic gradient of the COS price from
 * {@link HestonCos#parameterGradient} (forward-mode differentiation of the
 * characteristic function, as in Cui, del Bano Rollin and Germano 2017,
 * "Full and fast calibration of the Heston stochastic volatility model",
 * EJOR 263(2), who differentiate it by hand), divided by the same vega. The
 * step solves {@code (J'J + lambda diag(J'J)) delta = -J'r}; lambda is divided
 * by 3 after an accepted step and doubled after a rejected one. Parameters are
 * projected after each step onto the box {@code kappa in [1e-3, 50]},
 * {@code theta, v0 in [1e-4, 4]}, {@code sigmaV in [1e-3, 10]},
 * {@code rho in [-0.999, 0.999]}. The Feller condition is not imposed.</p>
 *
 * <p>Like any local least-squares fit, the result depends on the starting
 * point when the surface does not pin all five parameters (kappa and
 * sigmaV trade off on short-dated grids). The returned implied-vol RMSE is
 * computed by full inversion of the model prices (the vega proxy is used for a
 * quote whose model price cannot be inverted).</p>
 */
public final class HestonCalibrator {

    private static final double[] LO = {1e-3, 1e-4, 1e-3, -0.999, 1e-4};
    private static final double[] HI = {50, 4, 10, 0.999, 4};

    /**
     * Calibration outcome.
     *
     * @param params         fitted parameters
     * @param impliedVolRmse root-mean-square implied-vol error of the fit (vol units)
     * @param iterations     Levenberg-Marquardt iterations taken
     * @param converged      true when the gradient or the relative cost change fell below tolerance
     */
    public record Result(Heston.Params params, double impliedVolRmse, int iterations,
                         boolean converged) {
    }

    private HestonCalibrator() {
    }

    /** Calibrates with at most 200 iterations and tolerance 1e-12. */
    public static Result calibrate(double spot, double rate, double divYield, double[] strikes,
                                   double[] maturities, double[] impliedVols,
                                   Heston.Params initial) {
        return calibrate(spot, rate, divYield, strikes, maturities, impliedVols, initial, 200,
                1e-12);
    }

    /**
     * Calibrates to quotes {@code (strikes[i], maturities[i], impliedVols[i])}.
     *
     * @param maxIterations at least 1
     * @param tolerance     stop when the gradient's max-norm or the relative cost decrease is below it
     */
    public static Result calibrate(double spot, double rate, double divYield, double[] strikes,
                                   double[] maturities, double[] impliedVols,
                                   Heston.Params initial, int maxIterations, double tolerance) {
        int n = strikes.length;
        if (n < 5 || maturities.length != n || impliedVols.length != n) {
            throw new IllegalArgumentException("need at least 5 aligned quotes");
        }
        if (maxIterations < 1 || !(tolerance > 0)) {
            throw new IllegalArgumentException("need maxIterations >= 1 and tolerance > 0");
        }
        if (!(spot > 0) || !Double.isFinite(spot) || !Double.isFinite(rate)
                || !Double.isFinite(divYield)) {
            throw new IllegalArgumentException("invalid market inputs");
        }
        OptionType[] types = new OptionType[n];
        double[] target = new double[n];
        double[] vega = new double[n];
        for (int i = 0; i < n; i++) {
            double k = strikes[i], t = maturities[i], s = impliedVols[i];
            if (!(k > 0) || !(t > 0) || !(s > 0) || !Double.isFinite(k) || !Double.isFinite(t)
                    || !Double.isFinite(s)) {
                throw new IllegalArgumentException("quote " + i + " must have positive finite K, T, vol");
            }
            double fwd = spot * Math.exp((rate - divYield) * t);
            types[i] = k < fwd ? OptionType.PUT : OptionType.CALL;
            target[i] = BlackScholes.price(types[i], spot, k, rate, divYield, s, t);
            vega[i] = Math.max(BlackScholes.vega(spot, k, rate, divYield, s, t), 1e-8 * spot);
        }

        double[] p = project(new double[]{initial.kappa(), initial.theta(), initial.sigmaV(),
            initial.rho(), initial.v0()});
        double[] r = new double[n];
        double[][] jac = new double[n][HestonCos.NP];
        double cost = evaluate(p, spot, rate, divYield, strikes, maturities, types, target, vega,
                r, jac);
        double lambda = 1e-3;
        int iter = 0;
        boolean converged = false;
        double[] rTrial = new double[n];
        double[][] jTrial = new double[n][HestonCos.NP];
        while (iter < maxIterations) {
            iter++;
            double[][] a = new double[HestonCos.NP][HestonCos.NP];
            double[] g = new double[HestonCos.NP];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < HestonCos.NP; j++) {
                    g[j] += jac[i][j] * r[i];
                    for (int l = 0; l < HestonCos.NP; l++) {
                        a[j][l] += jac[i][j] * jac[i][l];
                    }
                }
            }
            double gMax = 0;
            for (double gj : g) {
                gMax = Math.max(gMax, Math.abs(gj));
            }
            if (gMax < tolerance) {
                converged = true;
                break;
            }
            boolean accepted = false;
            while (!accepted && lambda < 1e12) {
                double[][] m = new double[HestonCos.NP][HestonCos.NP];
                double[] rhs = new double[HestonCos.NP];
                for (int j = 0; j < HestonCos.NP; j++) {
                    System.arraycopy(a[j], 0, m[j], 0, HestonCos.NP);
                    m[j][j] += lambda * Math.max(a[j][j], 1e-12);
                    rhs[j] = -g[j];
                }
                double[] step = solve(m, rhs);
                double[] trial = new double[HestonCos.NP];
                for (int j = 0; j < HestonCos.NP; j++) {
                    trial[j] = p[j] + step[j];
                }
                trial = project(trial);
                double trialCost;
                try {
                    trialCost = evaluate(trial, spot, rate, divYield, strikes, maturities, types,
                            target, vega, rTrial, jTrial);
                } catch (ArithmeticException | IllegalArgumentException e) {
                    trialCost = Double.POSITIVE_INFINITY;
                }
                if (trialCost < cost) {
                    double rel = (cost - trialCost) / Math.max(cost, 1e-300);
                    p = trial;
                    cost = trialCost;
                    System.arraycopy(rTrial, 0, r, 0, n);
                    for (int i = 0; i < n; i++) {
                        System.arraycopy(jTrial[i], 0, jac[i], 0, HestonCos.NP);
                    }
                    lambda = Math.max(lambda / 3, 1e-12);
                    accepted = true;
                    if (rel < tolerance) {
                        converged = true;
                    }
                } else {
                    lambda *= 2;
                }
            }
            if (!accepted) {
                converged = true;    // no descent direction left at machine precision
                break;
            }
            if (converged) {
                break;
            }
        }

        Heston.Params fitted = new Heston.Params(p[0], p[1], p[2], p[3], p[4]);
        double se = 0;
        for (int i = 0; i < n; i++) {
            double model = HestonCos.price(types[i], spot, strikes[i], rate, divYield,
                    maturities[i], fitted);
            double iv = BlackScholes.impliedVol(types[i], model, spot, strikes[i], rate,
                    divYield, maturities[i]);
            // Price below the inversion's reach (far wing): fall back to the vega proxy.
            double e = Double.isFinite(iv) ? iv - impliedVols[i] : (model - target[i]) / vega[i];
            se += e * e;
        }
        return new Result(fitted, Math.sqrt(se / n), iter, converged);
    }

    private static double evaluate(double[] p, double spot, double rate, double divYield,
                                   double[] strikes, double[] maturities, OptionType[] types,
                                   double[] target, double[] vega, double[] r, double[][] jac) {
        Heston.Params params = new Heston.Params(p[0], p[1], p[2], p[3], p[4]);
        double cost = 0;
        for (int i = 0; i < r.length; i++) {
            double[] pg = HestonCos.priceAndGradient(types[i], spot, strikes[i], rate, divYield,
                    maturities[i], params);
            r[i] = (pg[0] - target[i]) / vega[i];
            for (int j = 0; j < HestonCos.NP; j++) {
                jac[i][j] = pg[1 + j] / vega[i];
            }
            cost += 0.5 * r[i] * r[i];
        }
        return cost;
    }

    private static double[] project(double[] p) {
        for (int j = 0; j < p.length; j++) {
            p[j] = Math.min(HI[j], Math.max(LO[j], p[j]));
        }
        return p;
    }

    /** Gaussian elimination with partial pivoting on a small dense system (destroys m, b). */
    private static double[] solve(double[][] m, double[] b) {
        int n = b.length;
        for (int c = 0; c < n; c++) {
            int piv = c;
            for (int i = c + 1; i < n; i++) {
                if (Math.abs(m[i][c]) > Math.abs(m[piv][c])) {
                    piv = i;
                }
            }
            double[] tr = m[c];
            m[c] = m[piv];
            m[piv] = tr;
            double tb = b[c];
            b[c] = b[piv];
            b[piv] = tb;
            for (int i = c + 1; i < n; i++) {
                double f = m[i][c] / m[c][c];
                for (int j = c; j < n; j++) {
                    m[i][j] -= f * m[c][j];
                }
                b[i] -= f * b[c];
            }
        }
        double[] x = new double[n];
        for (int i = n - 1; i >= 0; i--) {
            double s = b[i];
            for (int j = i + 1; j < n; j++) {
                s -= m[i][j] * x[j];
            }
            x[i] = s / m[i][i];
        }
        return x;
    }
}
