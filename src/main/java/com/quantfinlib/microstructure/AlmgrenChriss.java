package com.quantfinlib.microstructure;

import java.util.ArrayList;
import java.util.List;

/**
 * Almgren-Chriss (2000) optimal execution: the trading trajectory that
 * minimizes {@code E[cost] + λ·Var[cost]} when liquidating X shares over a
 * horizon, trading off temporary impact (fast execution is expensive) against
 * price risk (slow execution is risky). Closed-form discrete solution:
 * holdings decay as {@code x_j = X · sinh(κ(T - t_j)) / sinh(κT)}, with the
 * urgency parameter κ growing with risk aversion. λ→0 recovers TWAP.
 *
 * <p>Uses the same impact parameterization as {@link MarketImpactModel}:
 * temporary impact η (price concession per unit trade rate) and permanent
 * impact γ (per share). All quantities share one time unit (e.g. days):
 * σ is price volatility per √time, T the horizon in those units.</p>
 */
public final class AlmgrenChriss {

    /**
     * @param totalShares     X, the parent order size
     * @param horizon         T, execution horizon (in the chosen time unit)
     * @param intervals       N trading intervals
     * @param volatility      σ, price volatility per √time-unit (price units)
     * @param temporaryImpact η: price concession per unit of trade rate
     * @param permanentImpact γ: permanent price move per share traded
     * @param riskAversion    λ ≥ 0: variance penalty (0 = risk-neutral / TWAP)
     */
    public record Params(double totalShares, double horizon, int intervals,
                         double volatility, double temporaryImpact,
                         double permanentImpact, double riskAversion) {

        public Params {
            if (totalShares <= 0 || horizon <= 0 || intervals < 1) {
                throw new IllegalArgumentException("need positive size, horizon, intervals");
            }
            if (temporaryImpact <= 0 || riskAversion < 0 || volatility < 0) {
                throw new IllegalArgumentException("need eta > 0, lambda >= 0, sigma >= 0");
            }
        }

        public Params withRiskAversion(double lambda) {
            return new Params(totalShares, horizon, intervals, volatility,
                    temporaryImpact, permanentImpact, lambda);
        }
    }

    /**
     * The optimal schedule: {@code holdings[j]} is the position after
     * interval j (holdings[0] = X, holdings[N] = 0); {@code trades[j]} is
     * sold in interval j+1. Costs are in price·shares units versus the
     * arrival price.
     */
    public record Trajectory(double[] holdings, double[] trades, double kappa,
                             double expectedCost, double costVariance) {
    }

    private AlmgrenChriss() {
    }

    public static Trajectory optimalTrajectory(Params p) {
        int n = p.intervals();
        double tau = p.horizon() / n;
        double etaTilde = p.temporaryImpact() - 0.5 * p.permanentImpact() * tau;
        if (etaTilde <= 0) {
            throw new IllegalArgumentException(
                    "eta - gamma*tau/2 must be positive; shorten intervals or check impacts");
        }
        double kappa = 0;
        double lambdaSigma2 = p.riskAversion() * p.volatility() * p.volatility();
        if (lambdaSigma2 > 0) {
            double coshKappaTau = 1 + lambdaSigma2 * tau * tau / (2 * etaTilde);
            kappa = acosh(coshKappaTau) / tau;
        }

        double[] holdings = new double[n + 1];
        double x = p.totalShares();
        if (kappa * p.horizon() < 1e-9) {
            // Risk-neutral limit: linear (TWAP) trajectory.
            for (int j = 0; j <= n; j++) {
                holdings[j] = x * (1.0 - (double) j / n);
            }
        } else {
            double denom = Math.sinh(kappa * p.horizon());
            for (int j = 0; j <= n; j++) {
                holdings[j] = x * Math.sinh(kappa * (p.horizon() - j * tau)) / denom;
            }
        }
        holdings[n] = 0;

        double[] trades = new double[n];
        double expectedCost = 0.5 * p.permanentImpact() * x * x;
        double variance = 0;
        for (int j = 0; j < n; j++) {
            trades[j] = holdings[j] - holdings[j + 1];
            expectedCost += etaTilde / tau * trades[j] * trades[j];
            variance += p.volatility() * p.volatility() * tau
                    * holdings[j + 1] * holdings[j + 1];
        }
        return new Trajectory(holdings, trades, kappa, expectedCost, variance);
    }

    /** The risk-neutral (λ = 0) linear schedule, for comparison. */
    public static Trajectory twap(Params p) {
        return optimalTrajectory(p.withRiskAversion(0));
    }

    /** Cost/risk frontier across risk aversions (for choosing the urgency). */
    public static List<Trajectory> efficientFrontier(Params base, double[] riskAversions) {
        List<Trajectory> out = new ArrayList<>(riskAversions.length);
        for (double lambda : riskAversions) {
            out.add(optimalTrajectory(base.withRiskAversion(lambda)));
        }
        return out;
    }

    private static double acosh(double x) {
        return Math.log(x + Math.sqrt(x * x - 1));
    }
}
