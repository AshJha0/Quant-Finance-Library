package com.quantfinlib.pricing;

import java.util.SplittableRandom;

/**
 * SABR stochastic volatility model: Hagan et al. (2002) lognormal implied
 * volatility approximation and smile calibration. With β fixed (market
 * convention), calibrates (α, ρ, ν) to observed strike/vol quotes — turning
 * {@link VolSurface}-style pillar smiles into a parametric, arbitrage-aware
 * fit that inter/extrapolates sensibly.
 */
public final class SabrModel {

    /** Calibrated SABR parameters and the fit's RMSE in vol points. */
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

        if (Math.abs(logFk) < 1e-10) {
            return alpha / Math.pow(f, oneMinusBeta) * correction;
        }

        double z = nu / alpha * fkPow * logFk;
        double x = Math.log((Math.sqrt(1 - 2 * rho * z + z * z) + z - rho) / (1 - rho));
        double denom = fkPow * (1
                + oneMinusBeta * oneMinusBeta / 24 * logFk * logFk
                + Math.pow(oneMinusBeta, 4) / 1920 * Math.pow(logFk, 4));
        return alpha / denom * (z / x) * correction;
    }

    /**
     * Calibrates (α, ρ, ν) with β fixed, by seeded random search plus
     * shrinking coordinate refinement (derivative-free, deterministic).
     */
    public static Params calibrate(double f, double t, double beta,
                                   double[] strikes, double[] marketVols) {
        if (strikes.length != marketVols.length || strikes.length < 3) {
            throw new IllegalArgumentException("need >= 3 aligned strike/vol quotes");
        }
        // Initial alpha from the closest-to-ATM quote: vol_atm ≈ alpha / f^(1-beta).
        double atmVol = marketVols[0];
        double bestDist = Double.POSITIVE_INFINITY;
        for (int i = 0; i < strikes.length; i++) {
            double d = Math.abs(strikes[i] - f);
            if (d < bestDist) {
                bestDist = d;
                atmVol = marketVols[i];
            }
        }
        double alpha0 = atmVol * Math.pow(f, 1 - beta);

        SplittableRandom rnd = new SplittableRandom(42);
        double[] best = {alpha0, 0.0, 0.5};
        double bestSse = sse(f, t, beta, strikes, marketVols, best[0], best[1], best[2]);

        for (int s = 0; s < 4_000; s++) {
            double alpha = alpha0 * (0.4 + 1.6 * rnd.nextDouble());
            double rho = -0.98 + 1.96 * rnd.nextDouble();
            double nu = 0.01 + 2.99 * rnd.nextDouble();
            double err = sse(f, t, beta, strikes, marketVols, alpha, rho, nu);
            if (err < bestSse) {
                bestSse = err;
                best = new double[]{alpha, rho, nu};
            }
        }
        // Coordinate refinement with shrinking steps.
        double[] steps = {alpha0 * 0.1, 0.1, 0.1};
        for (int sweep = 0; sweep < 200; sweep++) {
            boolean improved = false;
            for (int p = 0; p < 3; p++) {
                for (int dir = -1; dir <= 1; dir += 2) {
                    double[] trial = best.clone();
                    trial[p] += dir * steps[p];
                    if (trial[0] <= 0 || Math.abs(trial[1]) >= 0.999 || trial[2] <= 0) {
                        continue;
                    }
                    double err = sse(f, t, beta, strikes, marketVols, trial[0], trial[1], trial[2]);
                    if (err < bestSse) {
                        bestSse = err;
                        best = trial;
                        improved = true;
                    }
                }
            }
            if (!improved) {
                for (int p = 0; p < 3; p++) {
                    steps[p] /= 2;
                }
                if (steps[1] < 1e-7) {
                    break;
                }
            }
        }
        return new Params(best[0], beta, best[1], best[2],
                Math.sqrt(bestSse / strikes.length));
    }

    private static double sse(double f, double t, double beta, double[] strikes,
                              double[] vols, double alpha, double rho, double nu) {
        double sum = 0;
        for (int i = 0; i < strikes.length; i++) {
            double d = impliedVol(f, strikes[i], t, alpha, beta, rho, nu) - vols[i];
            sum += d * d;
        }
        return sum;
    }
}
