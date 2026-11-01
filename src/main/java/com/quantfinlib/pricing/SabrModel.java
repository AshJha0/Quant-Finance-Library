package com.quantfinlib.pricing;

import com.quantfinlib.util.MathUtils;

/**
 * SABR stochastic volatility model: the Hagan, Kumar, Lesniewski and
 * Woodward (2002) lognormal implied-vol expansion ("Managing smile risk",
 * Wilmott, eq. 2.17a), and calibration of (α, ρ, ν) with β fixed.
 *
 * <p>Calibration is Levenberg-Marquardt (Marquardt 1963) on vol residuals,
 * optionally weighted, in the unconstrained variables ln α, atanh ρ, ln ν,
 * with a forward-difference Jacobian. {@link #calibrateToAtm} instead fixes
 * the ATM vol exactly by solving α from West's (2005) cubic ("Calibration of
 * the SABR model in illiquid markets", Applied Mathematical Finance 12) at
 * every iteration and fits (ρ, ν) to the rest of the smile.</p>
 *
 * <p>Hagan's expansion is not arbitrage-free: at low strikes and long
 * expiries the implied density ∂²C/∂K² can go negative. Nothing here
 * prevents that; {@link #minimumDensity} measures it on a strike grid so a
 * caller can reject or flag a fit.</p>
 */
public final class SabrModel {

    /**
     * Calibrated SABR parameters. {@code rmse} is the root-mean-square vol
     * error in decimal vol (0.001 = 0.1 vol point), unweighted.
     */
    public record Params(double alpha, double beta, double rho, double nu, double rmse) {
    }

    private SabrModel() {
    }

    /**
     * Hagan lognormal implied vol for forward {@code f}, strike {@code k},
     * expiry {@code t}.
     */
    public static double impliedVol(double f, double k, double t,
                                    double alpha, double beta, double rho, double nu) {
        if (f <= 0 || k <= 0 || t <= 0 || alpha <= 0) {
            throw new IllegalArgumentException("f, k, t, alpha must be positive");
        }
        double oneMinusBeta = 1 - beta;
        double logFk = Math.log(f / k);
        double fkPow = Math.pow(f * k, oneMinusBeta / 2);

        // Correction term (common to ATM and non-ATM).
        double correction = 1 + t * (
                oneMinusBeta * oneMinusBeta * alpha * alpha / (24 * fkPow * fkPow)
                        + rho * beta * nu * alpha / (4 * fkPow)
                        + (2 - 3 * rho * rho) * nu * nu / 24);

        double z = nu / alpha * fkPow * logFk;
        double zOverX;
        if (Math.abs(z) < 1e-8) {
            // z/x(z) = 1 + rho z/2 + O(z^2): avoids 0/0 near the money.
            zOverX = 1 - 0.5 * rho * z;
        } else {
            double x = Math.log((Math.sqrt(1 - 2 * rho * z + z * z) + z - rho) / (1 - rho));
            zOverX = z / x;
        }
        double denom = fkPow * (1
                + oneMinusBeta * oneMinusBeta / 24 * logFk * logFk
                + Math.pow(oneMinusBeta, 4) / 1920 * Math.pow(logFk, 4));
        return alpha / denom * zOverX * correction;
    }

    /**
     * α that reproduces an ATM vol exactly (West 2005, eq. 3): the smallest
     * positive root of
     * <pre>  (1−β)²T/(24 f^{2−2β}) α³ + ρβνT/(4 f^{1−β}) α² + (1 + (2−3ρ²)ν²T/24) α − σ_ATM f^{1−β} = 0</pre>
     */
    public static double alphaFromAtmVol(double f, double t, double beta, double rho, double nu,
                                         double atmVol) {
        if (!(f > 0) || !(t > 0) || !(atmVol > 0)) {
            throw new IllegalArgumentException("f, t, atmVol must be positive");
        }
        double fb = Math.pow(f, 1 - beta);
        double c3 = (1 - beta) * (1 - beta) * t / (24 * fb * fb);
        double c2 = rho * beta * nu * t / (4 * fb);
        double c1 = 1 + (2 - 3 * rho * rho) * nu * nu * t / 24;
        double c0 = -atmVol * fb;
        // Bracket the first sign change of the cubic on (0, hi] and polish.
        double hi = -c0 / Math.max(c1, 1e-12);
        double lo = 0;
        double step = hi / 64;
        double prev = c0;
        double a = step;
        boolean found = false;
        for (int i = 0; i < 64 * 64; i++, a += step) {
            double v = ((c3 * a + c2) * a + c1) * a + c0;
            if (v >= 0 && prev < 0) {
                lo = a - step;
                hi = a;
                found = true;
                break;
            }
            prev = v;
        }
        if (!found) {
            throw new IllegalArgumentException("no positive alpha reproduces atm vol " + atmVol);
        }
        for (int i = 0; i < 200 && hi - lo > 1e-16 * hi; i++) {
            double mid = 0.5 * (lo + hi);
            double v = ((c3 * mid + c2) * mid + c1) * mid + c0;
            if (v < 0) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return 0.5 * (lo + hi);
    }

    /** Unweighted calibration of (α, ρ, ν) with β fixed. */
    public static Params calibrate(double f, double t, double beta,
                                   double[] strikes, double[] marketVols) {
        return calibrate(f, t, beta, strikes, marketVols, null);
    }

    /**
     * Weighted least-squares calibration of (α, ρ, ν) with β fixed.
     *
     * @param weights per-quote weights (for example Black vegas, to fit
     *                prices rather than vols), or null for equal weights
     */
    public static Params calibrate(double f, double t, double beta, double[] strikes,
                                   double[] marketVols, double[] weights) {
        validate(f, t, strikes, marketVols, weights, 3);
        double atm = nearestVol(f, strikes, marketVols);
        double[] x0 = {Math.log(atm * Math.pow(f, 1 - beta)), atanh(-0.2), Math.log(0.5)};
        double[] x = levenbergMarquardt(x0, p -> residuals(f, t, beta, strikes, marketVols, weights,
                Math.exp(p[0]), Math.tanh(p[1]), Math.exp(p[2])), strikes.length);
        double alpha = Math.exp(x[0]), rho = Math.tanh(x[1]), nu = Math.exp(x[2]);
        return new Params(alpha, beta, rho, nu, rmse(f, t, beta, strikes, marketVols, alpha, rho, nu));
    }

    /**
     * Calibration that reprices the ATM vol exactly: α from
     * {@link #alphaFromAtmVol} for each trial (ρ, ν), and (ρ, ν) fitted by
     * Levenberg-Marquardt to the quotes.
     */
    public static Params calibrateToAtm(double f, double t, double beta, double atmVol,
                                        double[] strikes, double[] marketVols, double[] weights) {
        validate(f, t, strikes, marketVols, weights, 2);
        double[] x0 = {atanh(-0.2), Math.log(0.5)};
        double[] x = levenbergMarquardt(x0, p -> {
            double rho = Math.tanh(p[0]), nu = Math.exp(p[1]);
            double alpha = alphaFromAtmVol(f, t, beta, rho, nu, atmVol);
            return residuals(f, t, beta, strikes, marketVols, weights, alpha, rho, nu);
        }, strikes.length);
        double rho = Math.tanh(x[0]), nu = Math.exp(x[1]);
        double alpha = alphaFromAtmVol(f, t, beta, rho, nu, atmVol);
        return new Params(alpha, beta, rho, nu, rmse(f, t, beta, strikes, marketVols, alpha, rho, nu));
    }

    /**
     * Smallest risk-neutral density ∂²C/∂K² (undiscounted Black call on the
     * SABR vol, central second difference) over {@code n} strikes evenly
     * spaced on [kMin, kMax]. A negative value means the smile admits a
     * butterfly arbitrage there.
     */
    public static double minimumDensity(double f, double t, double alpha, double beta,
                                        double rho, double nu, double kMin, double kMax, int n) {
        if (!(kMin > 0) || !(kMax > kMin) || n < 2) {
            throw new IllegalArgumentException("need 0 < kMin < kMax and n >= 2");
        }
        double min = Double.POSITIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            double k = kMin + (kMax - kMin) * i / (n - 1);
            double h = 1e-3 * k;
            double cDn = call(f, k - h, t, alpha, beta, rho, nu);
            double c0 = call(f, k, t, alpha, beta, rho, nu);
            double cUp = call(f, k + h, t, alpha, beta, rho, nu);
            min = Math.min(min, (cUp - 2 * c0 + cDn) / (h * h));
        }
        return min;
    }

    private static double call(double f, double k, double t, double alpha, double beta,
                               double rho, double nu) {
        double v = impliedVol(f, k, t, alpha, beta, rho, nu);
        double sv = v * Math.sqrt(t);
        double d1 = Math.log(f / k) / sv + 0.5 * sv;
        return f * MathUtils.normCdf(d1) - k * MathUtils.normCdf(d1 - sv);
    }

    // ------------------------------------------------------------------ LM

    private static double[] levenbergMarquardt(double[] x0,
                                               java.util.function.Function<double[], double[]> res,
                                               int m) {
        int p = x0.length;
        double[] x = x0.clone();
        double[] r = res.apply(x);
        double cost = sumSq(r);
        double lambda = 1e-3;
        double[][] jac = new double[m][p];
        for (int iter = 0; iter < 500; iter++) {
            // Forward-difference Jacobian.
            for (int j = 0; j < p; j++) {
                double h = 1e-7 * Math.max(1, Math.abs(x[j]));
                double[] xh = x.clone();
                xh[j] += h;
                double[] rh = res.apply(xh);
                for (int i = 0; i < m; i++) {
                    jac[i][j] = (rh[i] - r[i]) / h;
                }
            }
            double[][] jtj = new double[p][p];
            double[] jtr = new double[p];
            for (int i = 0; i < m; i++) {
                for (int a = 0; a < p; a++) {
                    jtr[a] += jac[i][a] * r[i];
                    for (int b = 0; b < p; b++) {
                        jtj[a][b] += jac[i][a] * jac[i][b];
                    }
                }
            }
            boolean accepted = false;
            while (lambda < 1e12) {
                double[][] lhs = new double[p][p];
                double[] rhs = new double[p];
                for (int a = 0; a < p; a++) {
                    for (int b = 0; b < p; b++) {
                        lhs[a][b] = jtj[a][b];
                    }
                    lhs[a][a] += lambda * Math.max(jtj[a][a], 1e-12);
                    rhs[a] = -jtr[a];
                }
                double[] dx;
                try {
                    dx = MathUtils.solveLinear(lhs, rhs);
                } catch (RuntimeException e) {
                    lambda *= 10;
                    continue;
                }
                double[] xn = new double[p];
                for (int a = 0; a < p; a++) {
                    xn[a] = x[a] + dx[a];
                }
                double[] rn;
                try {
                    rn = res.apply(xn);
                } catch (IllegalArgumentException e) {
                    lambda *= 10;
                    continue;
                }
                double cn = sumSq(rn);
                if (Double.isFinite(cn) && cn < cost) {
                    double gain = cost - cn;
                    x = xn;
                    r = rn;
                    cost = cn;
                    lambda = Math.max(lambda / 10, 1e-12);
                    accepted = true;
                    if (gain <= 1e-15 * cost + 1e-30) {
                        return x;
                    }
                    break;
                }
                lambda *= 10;
            }
            if (!accepted) {
                return x;
            }
        }
        return x;
    }

    private static double[] residuals(double f, double t, double beta, double[] strikes,
                                      double[] vols, double[] weights, double alpha, double rho,
                                      double nu) {
        double[] r = new double[strikes.length];
        for (int i = 0; i < strikes.length; i++) {
            double w = weights == null ? 1 : Math.sqrt(weights[i]);
            r[i] = w * (impliedVol(f, strikes[i], t, alpha, beta, rho, nu) - vols[i]);
        }
        return r;
    }

    private static double rmse(double f, double t, double beta, double[] strikes, double[] vols,
                               double alpha, double rho, double nu) {
        return Math.sqrt(sumSq(residuals(f, t, beta, strikes, vols, null, alpha, rho, nu))
                / strikes.length);
    }

    private static double sumSq(double[] r) {
        double s = 0;
        for (double v : r) {
            s += v * v;
        }
        return s;
    }

    private static double atanh(double x) {
        return 0.5 * Math.log((1 + x) / (1 - x));
    }

    private static double nearestVol(double f, double[] strikes, double[] vols) {
        double best = vols[0];
        double bestDist = Double.POSITIVE_INFINITY;
        for (int i = 0; i < strikes.length; i++) {
            double d = Math.abs(strikes[i] - f);
            if (d < bestDist) {
                bestDist = d;
                best = vols[i];
            }
        }
        return best;
    }

    private static void validate(double f, double t, double[] strikes, double[] vols,
                                 double[] weights, int minQuotes) {
        if (!(f > 0) || !(t > 0)) {
            throw new IllegalArgumentException("f and t must be positive");
        }
        if (strikes.length != vols.length || strikes.length < minQuotes) {
            throw new IllegalArgumentException("need >= " + minQuotes + " aligned strike/vol quotes");
        }
        if (weights != null && weights.length != strikes.length) {
            throw new IllegalArgumentException("weights must align with strikes");
        }
        for (int i = 0; i < strikes.length; i++) {
            if (!(strikes[i] > 0) || !(vols[i] > 0) || !Double.isFinite(vols[i])) {
                throw new IllegalArgumentException("strikes and vols must be positive");
            }
            if (weights != null && !(weights[i] >= 0)) {
                throw new IllegalArgumentException("weights must be >= 0");
            }
        }
    }
}
