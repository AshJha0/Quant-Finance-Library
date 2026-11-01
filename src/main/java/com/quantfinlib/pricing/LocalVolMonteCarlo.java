package com.quantfinlib.pricing;

import com.quantfinlib.pricing.BlackScholes.OptionType;

import java.util.SplittableRandom;

/**
 * Monte Carlo under Dupire local volatility, log-Euler scheme:
 *
 * <pre>
 *   X_{n+1} = X_n + (r − q − ½σ_n²) Δt + σ_n √Δt Z_n,   σ_n = σ_loc(t_n, e^{X_n}),   X = ln S
 * </pre>
 *
 * <p>(Glasserman 2003, <i>Monte Carlo Methods in Financial Engineering</i>,
 * section 6.1). With constant σ the scheme is exact in distribution. The
 * local vol at each step is read from a table of {@link #GRID_POINTS}
 * log-moneyness nodes spanning ±8 ATM standard deviations of the final
 * expiry, linearly interpolated and held flat beyond; the table is built
 * once per call. Antithetic pairs; seeded {@link SplittableRandom}, so a
 * given seed reproduces the same price.</p>
 */
public final class LocalVolMonteCarlo {

    /** Local vol table size per time step. */
    public static final int GRID_POINTS = 401;

    /** Price and its Monte Carlo standard error. */
    public record Result(double price, double standardError) {
    }

    private final DupireLocalVol localVol;

    public LocalVolMonteCarlo(DupireLocalVol localVol) {
        if (localVol == null) {
            throw new IllegalArgumentException("localVol must not be null");
        }
        this.localVol = localVol;
    }

    /**
     * European option price.
     *
     * @param pathPairs number of antithetic pairs (&ge; 1)
     * @param steps     time steps (&ge; 1)
     */
    public Result price(OptionType type, double strike, double expiry, int pathPairs, int steps, long seed) {
        if (!(strike > 0) || !(expiry > 0) || pathPairs < 1 || steps < 1) {
            throw new IllegalArgumentException("strike, expiry > 0; pathPairs, steps >= 1");
        }
        TotalVarianceSurface s = localVol.surface();
        double r = s.rate();
        double q = s.carry();
        double dt = expiry / steps;
        double sqdt = Math.sqrt(dt);
        double half = 8 * Math.sqrt(s.totalVariance(expiry, 0));
        double[][] table = new double[steps][GRID_POINTS];
        double dy = 2 * half / (GRID_POINTS - 1);
        for (int n = 0; n < steps; n++) {
            double t = Math.max(n * dt, DupireLocalVol.MIN_TIME);
            for (int j = 0; j < GRID_POINTS; j++) {
                table[n][j] = localVol.localVolAtMoneyness(t, -half + j * dy);
            }
        }
        double x0 = Math.log(s.spot());
        double disc = Math.exp(-r * expiry);
        SplittableRandom rng = new SplittableRandom(seed);
        double[] z = new double[steps];
        double sum = 0;
        double sumSq = 0;
        for (int p = 0; p < pathPairs; p++) {
            for (int n = 0; n < steps; n++) {
                z[n] = rng.nextGaussian();
            }
            double pair = 0;
            for (int sign = -1; sign <= 1; sign += 2) {
                double x = x0;
                for (int n = 0; n < steps; n++) {
                    double t = n * dt;
                    double y = x - x0 - (r - q) * t;
                    double sig = lookup(table[n], (y + half) / dy);
                    x += (r - q - 0.5 * sig * sig) * dt + sig * sqdt * sign * z[n];
                }
                pair += 0.5 * Math.max(0, type.sign() * (Math.exp(x) - strike));
            }
            sum += pair;
            sumSq += pair * pair;
        }
        double mean = sum / pathPairs;
        double var = pathPairs > 1 ? Math.max(0, (sumSq - pathPairs * mean * mean) / (pathPairs - 1)) : 0;
        return new Result(disc * mean, disc * Math.sqrt(var / pathPairs));
    }

    private static double lookup(double[] row, double pos) {
        if (pos <= 0) {
            return row[0];
        }
        int last = row.length - 1;
        if (pos >= last) {
            return row[last];
        }
        int i = (int) pos;
        double u = pos - i;
        return row[i] + u * (row[i + 1] - row[i]);
    }
}
