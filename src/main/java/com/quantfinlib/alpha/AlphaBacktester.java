package com.quantfinlib.alpha;

import com.quantfinlib.backtest.PerformanceAnalytics;
import com.quantfinlib.backtest.PerformanceMetrics;
import com.quantfinlib.core.BarSeries;
import com.quantfinlib.microstructure.MarketImpactModel;
import com.quantfinlib.util.MathUtils;

import java.util.List;

/**
 * Execution-aware factor backtest: runs a factor through a construction
 * pipeline into weights, holds them between rebalances, and charges the
 * four costs that separate paper alpha from real alpha:
 *
 * <ul>
 *   <li><b>Commission</b> — flat bps on traded notional;</li>
 *   <li><b>Bid-ask spread</b> — half-spread bps paid on every trade
 *       (crossing the spread once per side);</li>
 *   <li><b>Slippage</b> — additional fixed bps of implementation noise
 *       (latency, partial fills, venue fees);</li>
 *   <li><b>Market impact</b> — the size-dependent cost, via the
 *       square-root law in {@code microstructure.MarketImpactModel}, with
 *       per-symbol ADV and daily vol estimated from the trailing window.
 *       This is the term that grows with capital: the same signal that
 *       nets 8% on $10m can net zero on $1b.</li>
 * </ul>
 *
 * <p>The simulation is <em>weight-based</em> (fractional book, returns
 * compound multiplicatively from 1.0): simpler and adequate for factor
 * research. For share-level accounting with lifecycle events, feed the
 * constructed weights into {@code backtest.portfolio.PortfolioBacktester}'s
 * survivorship-aware overload instead — the two engines are complementary,
 * not duplicates.</p>
 *
 * <p>Both gross and net curves are tracked, plus the cumulative drag of
 * each cost component — "which cost kills this signal" is the actionable
 * output of an execution-aware backtest.</p>
 */
public final class AlphaBacktester {

    /** Builds target weights from raw scores at a rebalance (the construction hook). */
    @FunctionalInterface
    public interface WeightBuilder {
        double[] weights(AlphaContext ctx, double[] scores, int index);
    }

    /**
     * @param startIndex         first tradeable bar (must cover factor warm-up
     *                           and the impact estimation window)
     * @param rebalanceEveryBars holding period between weight refreshes
     * @param commissionBps      commission per side on traded notional
     * @param halfSpreadBps      half the quoted spread, paid per trade
     * @param slippageBps        fixed implementation noise per trade
     * @param capital            book size in currency — impact needs real size
     *                           (0 disables the impact model entirely)
     * @param impactWindow       trailing bars for ADV/vol estimation
     * @param periodsPerYear     bar frequency for annualized metrics
     */
    public record Config(int startIndex, int rebalanceEveryBars, double commissionBps,
                         double halfSpreadBps, double slippageBps, double capital,
                         int impactWindow, int periodsPerYear) {

        public Config {
            if (startIndex < 0 || rebalanceEveryBars <= 0 || commissionBps < 0
                    || halfSpreadBps < 0 || slippageBps < 0 || capital < 0
                    || impactWindow < 2 || periodsPerYear <= 0) {
                throw new IllegalArgumentException("invalid backtest config");
            }
        }

        /** Institutional-ish daily-bar defaults: 1bp commission, 2bp half-spread, 1bp slippage, $100m book. */
        public static Config defaults(int startIndex) {
            return new Config(startIndex, 21, 1.0, 2.0, 1.0, 100_000_000, 20, 252);
        }
    }

    /**
     * Net/gross curves plus the cumulative fraction of equity each cost
     * component consumed — the cost autopsy.
     */
    public record Result(double[] netEquity, double[] grossEquity, PerformanceMetrics netMetrics,
                         PerformanceMetrics grossMetrics, double commissionDrag, double spreadDrag,
                         double slippageDrag, double impactDrag, double meanTurnover) {

        /** Total cost drag: gross minus net, decomposed. */
        public double totalCostDrag() {
            return commissionDrag + spreadDrag + slippageDrag + impactDrag;
        }
    }

    private AlphaBacktester() {
    }

    /** Runs with the standard z-score construction (gross 1.0, 5% name cap). */
    public static Result run(AlphaContext ctx, AlphaFactor factor, Config config) {
        return run(ctx, factor, config,
                (c, scores, index) -> PortfolioConstruction.zScoreWeights(scores, 1.0, 0.05));
    }

    /** Runs with a caller-supplied construction pipeline. */
    public static Result run(AlphaContext ctx, AlphaFactor factor, Config config,
                             WeightBuilder builder) {
        int n = ctx.bars();
        int m = ctx.symbolCount();
        if (config.startIndex() >= n - 1) {
            throw new IllegalArgumentException("startIndex leaves no bars to trade");
        }
        double[] weights = new double[m];       // current holdings (fractions of equity)
        double[] netEquity = new double[n - config.startIndex()];
        double[] grossEquity = new double[netEquity.length];
        netEquity[0] = 1.0;
        grossEquity[0] = 1.0;
        double commissionDrag = 0;
        double spreadDrag = 0;
        double slippageDrag = 0;
        double impactDrag = 0;
        double turnoverSum = 0;
        int rebalances = 0;

        for (int t = config.startIndex(); t < n - 1; t++) {
            int outIdx = t - config.startIndex();

            // Rebalance at the bar close, before earning the next bar's return.
            if ((t - config.startIndex()) % config.rebalanceEveryBars() == 0) {
                double[] target = builder.weights(ctx, factor.scores(ctx, t), t);
                if (target.length != m) {
                    throw new IllegalArgumentException("weight builder returned misaligned array");
                }
                double turnover = 0;
                double commission = 0;
                double spread = 0;
                double slip = 0;
                double impact = 0;
                for (int i = 0; i < m; i++) {
                    double traded = Math.abs(target[i] - weights[i]);
                    if (traded == 0) {
                        continue;
                    }
                    turnover += traded;
                    // Flat per-trade costs scale with traded fraction of equity.
                    commission += traded * config.commissionBps() / 1e4;
                    spread += traded * config.halfSpreadBps() / 1e4;
                    slip += traded * config.slippageBps() / 1e4;
                    // Impact needs real size: traded fraction × capital → shares.
                    if (config.capital() > 0) {
                        impact += traded * impactBps(ctx, i, t, traded, config) / 1e4;
                    }
                }
                double cost = commission + spread + slip + impact;
                // Costs come straight out of net equity at the rebalance.
                netEquity[outIdx] *= 1 - cost;
                commissionDrag += commission;
                spreadDrag += spread;
                slippageDrag += slip;
                impactDrag += impact;
                turnoverSum += turnover / 2; // buys and sells counted once
                rebalances++;
                System.arraycopy(target, 0, weights, 0, m);
            }

            // Earn one bar of the held book: r_p = Σ wᵢ · rᵢ.
            double portfolioReturn = 0;
            for (int i = 0; i < m; i++) {
                if (weights[i] != 0) {
                    portfolioReturn += weights[i] * ctx.returnOver(i, t, t + 1);
                }
            }
            netEquity[outIdx + 1] = netEquity[outIdx] * (1 + portfolioReturn);
            grossEquity[outIdx + 1] = grossEquity[outIdx] * (1 + portfolioReturn);
        }

        return new Result(netEquity, grossEquity,
                PerformanceAnalytics.compute(netEquity, List.of(), config.periodsPerYear()),
                PerformanceAnalytics.compute(grossEquity, List.of(), config.periodsPerYear()),
                commissionDrag, spreadDrag, slippageDrag, impactDrag,
                rebalances == 0 ? 0 : turnoverSum / rebalances);
    }

    /**
     * Square-root-law impact for one name's trade: estimate ADV and daily
     * vol from the trailing window, convert the traded equity fraction to
     * shares, and ask {@code MarketImpactModel}. Names without volume data
     * contribute zero impact (documented: impact needs ADV; absence of
     * volume is a data gap, not free liquidity — flat costs still apply).
     */
    private static double impactBps(AlphaContext ctx, int symbol, int index, double tradedFraction,
                                    Config config) {
        BarSeries s = ctx.series(symbol);
        int window = config.impactWindow();
        double advSum = 0;
        double[] returns = new double[window];
        for (int j = 0; j < window; j++) {
            advSum += s.volume(index - window + j + 1);
            returns[j] = ctx.returnOver(symbol, index - window + j, index - window + j + 1);
        }
        double adv = advSum / window;
        if (adv <= 0) {
            return 0;
        }
        double dailyVol = MathUtils.stdDev(returns);
        double shares = tradedFraction * config.capital() / s.close(index);
        return new MarketImpactModel(adv, dailyVol).squareRootImpactBps(shares);
    }
}
