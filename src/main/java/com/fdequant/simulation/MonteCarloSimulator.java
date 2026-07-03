package com.fdequant.simulation;

import com.fdequant.util.MathUtils;

import java.util.SplittableRandom;
import java.util.stream.IntStream;

/**
 * Monte Carlo Portfolio Simulation. Runs tens of thousands to hundreds of
 * thousands of GBM scenarios in parallel across all cores; results are
 * deterministic for a given seed regardless of thread scheduling.
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
     * Single-asset / whole-portfolio GBM simulation with daily steps.
     *
     * @param initialValue starting portfolio value
     * @param annualReturn annualized drift (e.g. 0.08)
     * @param annualVol    annualized volatility (e.g. 0.15)
     * @param horizonDays  trading days to simulate
     * @param simulations  number of scenarios (10_000+ recommended)
     */
    public SimulationResult simulate(double initialValue, double annualReturn, double annualVol,
                                     int horizonDays, int simulations) {
        final double dt = 1.0 / 252;
        final double drift = (annualReturn - 0.5 * annualVol * annualVol) * dt;
        final double diffusion = annualVol * Math.sqrt(dt);

        double[] finals = new double[simulations];
        IntStream.range(0, simulations).parallel().forEach(s -> {
            SplittableRandom rnd = new SplittableRandom(mix(seed, s));
            double logV = Math.log(initialValue);
            for (int d = 0; d < horizonDays; d++) {
                logV += drift + diffusion * gaussian(rnd);
            }
            finals[s] = Math.exp(logV);
        });
        return new SimulationResult(initialValue, finals);
    }

    /**
     * Correlated multi-asset portfolio simulation using daily mean returns and
     * daily covariance (e.g. estimated from historical returns).
     */
    public SimulationResult simulatePortfolio(double initialValue, double[] weights,
                                              double[] dailyMeanReturns, double[][] dailyCovariance,
                                              int horizonDays, int simulations) {
        final int k = weights.length;
        final double[][] chol = MathUtils.cholesky(dailyCovariance);

        double[] finals = new double[simulations];
        IntStream.range(0, simulations).parallel().forEach(s -> {
            SplittableRandom rnd = new SplittableRandom(mix(seed, s));
            double value = initialValue;
            double[] z = new double[k];
            for (int d = 0; d < horizonDays; d++) {
                for (int i = 0; i < k; i++) {
                    z[i] = gaussian(rnd);
                }
                double portReturn = 0;
                for (int i = 0; i < k; i++) {
                    double r = dailyMeanReturns[i];
                    for (int j = 0; j <= i; j++) {
                        r += chol[i][j] * z[j];
                    }
                    portReturn += weights[i] * r;
                }
                value *= (1 + portReturn);
                if (value <= 0) {
                    value = 0;
                    break;
                }
            }
            finals[s] = value;
        });
        return new SimulationResult(initialValue, finals);
    }

    private static long mix(long seed, int stream) {
        long z = seed + (stream + 1L) * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Marsaglia polar method — ~2x faster than Box-Muller with trig calls. */
    private static double gaussian(SplittableRandom rnd) {
        double u, v, s;
        do {
            u = 2 * rnd.nextDouble() - 1;
            v = 2 * rnd.nextDouble() - 1;
            s = u * u + v * v;
        } while (s >= 1 || s == 0);
        return u * Math.sqrt(-2 * Math.log(s) / s);
    }
}
