package com.quantfinlib.hedging;

import com.quantfinlib.pricing.BlackScholes;
import com.quantfinlib.pricing.BlackScholes.OptionType;

import java.util.SplittableRandom;
import java.util.stream.IntStream;

/**
 * Monte Carlo delta-hedging engine: runs {@link DeltaHedger} across thousands
 * of GBM paths in parallel to produce the full hedging-error distribution —
 * replication error, tail risk (hedging VaR/CVaR), and cost/rebalance
 * statistics.
 *
 * <p>Pricing/hedging volatility and realized (path) volatility are separate
 * inputs, so the two classic questions are directly answerable:</p>
 * <ul>
 *   <li><b>Discretization risk</b> — hedge vol = realized vol: how does the
 *       error distribution shrink with rebalancing frequency and band?</li>
 *   <li><b>Vol mispricing</b> — hedge vol ≠ realized vol: selling rich
 *       (implied &gt; realized) shows up as a positive mean P&amp;L, and vice
 *       versa.</li>
 * </ul>
 *
 * Deterministic for a given seed regardless of thread scheduling.
 */
public final class HedgingSimulator {

    private final long seed;

    public HedgingSimulator() {
        this(7L);
    }

    public HedgingSimulator(long seed) {
        this.seed = seed;
    }

    /**
     * @param hedgeVol     volatility used to price and delta-hedge (implied vol)
     * @param realizedVol  volatility of the simulated underlying paths
     * @param stepsPerPath hedging opportunities per path (e.g. 126 for daily over 6 months)
     * @param numPaths     Monte Carlo scenarios (2 000+ recommended)
     */
    public HedgingErrorDistribution simulate(OptionType type, double spot, double strike,
                                             double expiryYears, double rate, double carry,
                                             double hedgeVol, double realizedVol,
                                             int stepsPerPath, int numPaths,
                                             DeltaHedger.Config hedgeConfig) {
        if (stepsPerPath < 1 || numPaths < 1) {
            throw new IllegalArgumentException("need positive steps and paths");
        }
        final double dt = expiryYears / stepsPerPath;
        final double drift = (rate - carry - 0.5 * realizedVol * realizedVol) * dt;
        final double diffusion = realizedVol * Math.sqrt(dt);
        final double premium = BlackScholes.price(type, spot, strike, rate, carry, hedgeVol, expiryYears);

        double[] pnls = new double[numPaths];
        double[] costs = new double[numPaths];
        int[] rebalances = new int[numPaths];

        IntStream.range(0, numPaths).parallel().forEach(p -> {
            SplittableRandom rnd = new SplittableRandom(mix(seed, p));
            double[] path = new double[stepsPerPath + 1];
            path[0] = spot;
            for (int i = 1; i <= stepsPerPath; i++) {
                path[i] = path[i - 1] * Math.exp(drift + diffusion * gaussian(rnd));
            }
            DeltaHedger.HedgeReport report = DeltaHedger.simulateShortOption(
                    type, strike, expiryYears, rate, carry, hedgeVol, path, dt, hedgeConfig);
            pnls[p] = report.finalPnl();
            costs[p] = report.tradingCosts();
            rebalances[p] = report.rebalances();
        });

        return new HedgingErrorDistribution(premium, pnls, costs, rebalances);
    }

    private static long mix(long seed, int stream) {
        long z = seed + (stream + 1L) * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Marsaglia polar method. */
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
