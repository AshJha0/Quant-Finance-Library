package com.quantfinlib.hedging;

import com.quantfinlib.pricing.BlackScholes;
import com.quantfinlib.pricing.BlackScholes.OptionType;

/**
 * Dynamic delta hedging simulator: sell an option, then replicate it by
 * trading the underlying along a price path, rebalancing whenever the delta
 * drifts outside a band. Quantifies the trade-off every option desk lives
 * with — tighter bands mean smaller replication error but more transaction
 * costs.
 *
 * <p>The final P&amp;L is the hedging error: zero in the Black-Scholes limit
 * of continuous, costless rebalancing at the true volatility; in practice it
 * spreads with rebalancing interval, transaction costs, and the gap between
 * implied and realized volatility.</p>
 *
 * <p>Accounting: the premium goes into a cash account that compounds at
 * {@code rate}; the shares held earn the continuous {@code carry} (paid
 * into cash), consistent with the Black-Scholes-Merton drift {@code r − q}
 * used for pricing (Hull, Options, Futures and Other Derivatives, ch. 17).
 * Without the carry leg a q = 3% hedge would show a mean loss of roughly
 * {@code ∫ Δ·S·q dt}.</p>
 */
public final class DeltaHedger {

    /**
     * @param deltaBand          rebalance only when |target - held| delta exceeds this (0 = every step)
     * @param transactionCostBps cost per rebalance trade, in bps of traded notional
     */
    public record Config(double deltaBand, double transactionCostBps) {

        public static Config every(double transactionCostBps) {
            return new Config(0, transactionCostBps);
        }
    }

    public record HedgeReport(
            double premium,          // option premium received at inception
            double payoff,           // option payoff settled at expiry
            double tradingCosts,     // total transaction costs paid
            int rebalances,          // number of hedge trades (including the initial one)
            double turnover,         // total |delta| traded
            double finalPnl) {       // replication error: hedge portfolio - payoff
    }

    private DeltaHedger() {
    }

    /**
     * Simulates a short option delta-hedged along the given path.
     *
     * @param path        underlying prices; {@code path[0]} at inception,
     *                    {@code path[n-1]} at expiry
     * @param dtYears     time between path points (e.g. 1/252 for daily)
     * @param rate        continuously compounded cash rate (annual)
     * @param carry       continuous yield paid by the underlying (dividend
     *                    yield, or the foreign rate for FX), annual; it is
     *                    credited to the hedge position each step
     * @param hedgeVol    volatility used for pricing and hedging (implied vol)
     * @param expiryYears option expiry; should equal {@code (path.length-1) * dtYears}
     */
    public static HedgeReport simulateShortOption(OptionType type, double strike, double expiryYears,
                                                  double rate, double carry, double hedgeVol,
                                                  double[] path, double dtYears, Config config) {
        int n = path.length;
        if (n < 2) {
            throw new IllegalArgumentException("need at least two path points");
        }
        double premium = BlackScholes.price(type, path[0], strike, rate, carry, hedgeVol, expiryYears);
        double cash = premium;
        double held = 0;
        double costs = 0;
        double turnover = 0;
        int rebalances = 0;

        for (int i = 0; i < n - 1; i++) {
            double remaining = expiryYears - i * dtYears;
            double target = BlackScholes.delta(type, path[i], strike, rate, carry, hedgeVol, remaining);
            if (i == 0 || Math.abs(target - held) > config.deltaBand()) {
                double d = target - held;
                cash -= d * path[i];
                double fee = Math.abs(d) * path[i] * config.transactionCostBps() / 1e4;
                cash -= fee;
                costs += fee;
                turnover += Math.abs(d);
                rebalances++;
                held = target;
            }
            // Cash accrues at the rate; the shares held over the step pay the
            // continuous carry (dividend yield, or foreign rate for FX) into
            // cash at the step end. Holding h shares from t_i to t_{i+1} has
            // total return S_{i+1}·e^{q·dt}, so the payment is
            // h·S_{i+1}·(e^{q·dt} − 1); a short hedge (h < 0) pays it.
            cash = cash * Math.exp(rate * dtYears)
                    + held * path[i + 1] * Math.expm1(carry * dtYears);
        }

        double finalSpot = path[n - 1];
        double payoff = BlackScholes.intrinsic(type, finalSpot, strike);
        double finalPnl = cash + held * finalSpot - payoff;
        return new HedgeReport(premium, payoff, costs, rebalances, turnover, finalPnl);
    }
}
