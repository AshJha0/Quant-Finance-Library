package com.quantfinlib.simulation;

import com.quantfinlib.risk.PsdMatrices;

import java.util.SplittableRandom;
import java.util.stream.IntStream;

/**
 * Monte Carlo simulation of geometric Brownian motion, single- and
 * multi-asset, in parallel; results are deterministic for a given seed
 * regardless of thread scheduling (each scenario has its own
 * {@link SplittableRandom} stream derived from the seed).
 *
 * <ul>
 *   <li>{@link #simulate} and {@link #simulatePortfolio} return terminal
 *       values for risk analytics ({@link SimulationResult}). Both sample
 *       the exact lognormal terminal distribution, so there is no
 *       time-discretisation error.</li>
 *   <li>{@link #simulatePaths} generates correlated multi-asset GBM paths
 *       on a uniform grid with exact log-Euler steps and hands each path to
 *       a caller payoff (barriers, Asians, autocalls, baskets), with
 *       optional antithetic variates (Glasserman, Monte Carlo Methods in
 *       Financial Engineering, 2003, §4.2).</li>
 * </ul>
 *
 * <p>Model per asset i: {@code dS_i/S_i = b_i dt + σ_i dW_i},
 * {@code d⟨W_i, W_j⟩ = ρ_ij dt}. For pricing pass the risk-neutral drift
 * {@code b_i = r − q_i} and discount the payoff yourself (or use
 * {@link #price}); for risk pass the real-world drift.</p>
 */
public final class MonteCarloSimulator {

    private final long seed;

    public MonteCarloSimulator() {
        this(7L);
    }

    public MonteCarloSimulator(long seed) {
        this.seed = seed;
    }

    /**
     * Single-asset GBM terminal values after {@code horizonDays} trading
     * days ({@code T = horizonDays/252}): {@code V_T = V_0·exp((μ − σ²/2)T + σ√T·Z)}.
     *
     * @param initialValue starting value, &gt; 0
     * @param annualReturn annualized drift μ (so {@code E[V_T] = V_0·e^{μT}})
     * @param annualVol    annualized volatility σ, &ge; 0
     * @param horizonDays  trading days to simulate, &ge; 1
     * @param simulations  number of scenarios, &ge; 1
     */
    public SimulationResult simulate(double initialValue, double annualReturn, double annualVol,
                                     int horizonDays, int simulations) {
        if (!(initialValue > 0) || !Double.isFinite(annualReturn) || !(annualVol >= 0)
                || horizonDays < 1 || simulations < 1) {
            throw new IllegalArgumentException("need initialValue > 0, finite drift, vol >= 0, "
                    + "horizonDays >= 1, simulations >= 1");
        }
        final double t = horizonDays / 252.0;
        final double drift = (annualReturn - 0.5 * annualVol * annualVol) * t;
        final double diffusion = annualVol * Math.sqrt(t);
        double[] finals = new double[simulations];
        IntStream.range(0, simulations).parallel().forEach(s -> {
            SplittableRandom rnd = new SplittableRandom(mix(seed, s));
            finals[s] = initialValue * Math.exp(drift + diffusion * rnd.nextGaussian());
        });
        return new SimulationResult(initialValue, finals);
    }

    /**
     * Buy-and-hold multi-asset portfolio. Daily log returns are jointly
     * normal with mean {@code dailyMeanLogReturns} and covariance
     * {@code dailyCovariance}, so the terminal log return of asset i over
     * {@code d} days is {@code N(d·m_i, d·Σ_ii)} and the portfolio is worth
     * {@code V_0·Σ w_i·exp(X_i)}. Long-only weights keep the value positive;
     * short weights can drive it below zero, and it is not clamped (there is
     * no absorbing bankruptcy state in GBM).
     *
     * @param weights           initial value weights (need not sum to 1)
     * @param dailyCovariance   PSD covariance of daily log returns
     */
    public SimulationResult simulatePortfolio(double initialValue, double[] weights,
                                              double[] dailyMeanLogReturns,
                                              double[][] dailyCovariance,
                                              int horizonDays, int simulations) {
        final int k = weights.length;
        if (dailyMeanLogReturns.length != k || dailyCovariance.length != k || horizonDays < 1
                || simulations < 1 || !(initialValue > 0)) {
            throw new IllegalArgumentException("dimension mismatch or bad horizon/simulations/value");
        }
        final double[][] chol = PsdMatrices.choleskyPsd(dailyCovariance);
        final double sqrtD = Math.sqrt(horizonDays);
        double[] finals = new double[simulations];
        IntStream.range(0, simulations).parallel().forEach(s -> {
            SplittableRandom rnd = new SplittableRandom(mix(seed, s));
            double[] z = new double[k];
            for (int i = 0; i < k; i++) {
                z[i] = rnd.nextGaussian();
            }
            double value = 0;
            for (int i = 0; i < k; i++) {
                double x = horizonDays * dailyMeanLogReturns[i];
                for (int j = 0; j <= i; j++) {
                    x += sqrtD * chol[i][j] * z[j];
                }
                value += weights[i] * Math.exp(x);
            }
            finals[s] = initialValue * value;
        });
        return new SimulationResult(initialValue, finals);
    }

    // ------------------------------------------------------------------
    // Path simulation
    // ------------------------------------------------------------------

    /** Payoff of one simulated path. */
    @FunctionalInterface
    public interface PathPayoff {
        /**
         * Payoff of one simulated path.
         *
         * @param path {@code path[i][j]} = price of asset i at grid point j,
         *             j = 0 (spot) .. steps (maturity). The array is reused
         *             by the caller thread; copy anything you keep.
         * @return the (undiscounted) payoff, finite
         */
        double payoff(double[][] path);
    }

    /** Monte Carlo estimate: mean, its standard error, and the number of independent samples. */
    public record Estimate(double mean, double standardError, int samples) {
    }

    /**
     * Simulates {@code paths} correlated GBM paths and returns the payoff of
     * each. With {@code antithetic}, paths come in pairs driven by
     * {@code Z} and {@code −Z}; entry {@code 2p} and {@code 2p+1} are a pair
     * ({@code paths} must then be even).
     *
     * @param spots       S_i(0) &gt; 0
     * @param drifts      b_i per year (r − q_i for pricing)
     * @param vols        σ_i ≥ 0 per year
     * @param correlation PSD correlation matrix (unit diagonal)
     * @param maturity    T in years, &gt; 0
     * @param steps       grid points after spot, ≥ 1 (Δt = T/steps)
     */
    public double[] simulatePaths(double[] spots, double[] drifts, double[] vols,
                                  double[][] correlation, double maturity, int steps,
                                  int paths, boolean antithetic, PathPayoff payoff) {
        final int k = spots.length;
        if (drifts.length != k || vols.length != k || correlation.length != k || k < 1) {
            throw new IllegalArgumentException("dimension mismatch");
        }
        for (int i = 0; i < k; i++) {
            if (!(spots[i] > 0) || !Double.isFinite(drifts[i]) || !(vols[i] >= 0)
                    || Math.abs(correlation[i][i] - 1) > 1e-12) {
                throw new IllegalArgumentException("bad inputs for asset " + i);
            }
        }
        if (!(maturity > 0) || steps < 1 || paths < 1 || (antithetic && paths % 2 != 0)) {
            throw new IllegalArgumentException("need maturity > 0, steps >= 1, paths >= 1 "
                    + "(even when antithetic)");
        }
        final double[][] chol = PsdMatrices.choleskyPsd(correlation);
        final double dt = maturity / steps;
        final double sqrtDt = Math.sqrt(dt);
        final double[] mu = new double[k];
        for (int i = 0; i < k; i++) {
            mu[i] = (drifts[i] - 0.5 * vols[i] * vols[i]) * dt;
        }
        final int streams = antithetic ? paths / 2 : paths;
        double[] out = new double[paths];
        IntStream.range(0, streams).parallel().forEach(s -> {
            SplittableRandom rnd = new SplittableRandom(mix(seed, s));
            double[][] z = new double[steps][k];
            for (int j = 0; j < steps; j++) {
                for (int i = 0; i < k; i++) {
                    z[j][i] = rnd.nextGaussian();
                }
            }
            double[][] path = new double[k][steps + 1];
            int reps = antithetic ? 2 : 1;
            for (int a = 0; a < reps; a++) {
                double sign = a == 0 ? 1 : -1;
                for (int i = 0; i < k; i++) {
                    path[i][0] = spots[i];
                }
                double[] logS = new double[k];
                for (int i = 0; i < k; i++) {
                    logS[i] = Math.log(spots[i]);
                }
                for (int j = 0; j < steps; j++) {
                    for (int i = 0; i < k; i++) {
                        double w = 0;
                        for (int m = 0; m <= i; m++) {
                            w += chol[i][m] * z[j][m];
                        }
                        logS[i] += mu[i] + vols[i] * sqrtDt * sign * w;
                        path[i][j + 1] = Math.exp(logS[i]);
                    }
                }
                double v = payoff.payoff(path);
                if (!Double.isFinite(v)) {
                    throw new IllegalArgumentException("payoff returned " + v + " on path " + s);
                }
                out[antithetic ? 2 * s + a : s] = v;
            }
        });
        return out;
    }

    /**
     * Discounted Monte Carlo price {@code e^{−rT}·E[payoff]} with its
     * standard error. Under antithetic sampling the error is computed on the
     * pair averages, which are the independent samples.
     */
    public Estimate price(double[] spots, double[] drifts, double[] vols, double[][] correlation,
                          double maturity, int steps, int paths, boolean antithetic,
                          double discountRate, PathPayoff payoff) {
        double[] v = simulatePaths(spots, drifts, vols, correlation, maturity, steps, paths,
                antithetic, payoff);
        int m = antithetic ? paths / 2 : paths;
        double df = Math.exp(-discountRate * maturity);
        double mean = 0;
        double[] x = new double[m];
        for (int i = 0; i < m; i++) {
            x[i] = antithetic ? 0.5 * (v[2 * i] + v[2 * i + 1]) : v[i];
            mean += x[i];
        }
        mean /= m;
        double ss = 0;
        for (double xi : x) {
            ss += (xi - mean) * (xi - mean);
        }
        double se = m > 1 ? Math.sqrt(ss / (m - 1) / m) : Double.POSITIVE_INFINITY;
        return new Estimate(df * mean, df * se, m);
    }

    private static long mix(long seed, int stream) {
        long z = seed + (stream + 1L) * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
