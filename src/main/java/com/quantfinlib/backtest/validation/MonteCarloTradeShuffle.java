package com.quantfinlib.backtest.validation;

import com.quantfinlib.backtest.Trade;
import com.quantfinlib.util.MathUtils;

import java.util.List;
import java.util.SplittableRandom;

/**
 * Monte Carlo trade reshuffling: the answer to "was my equity curve's
 * shape luck?". A backtest reports one path: this strategy's trades in
 * the order they happened, producing one max drawdown and one terminal
 * equity. But the order is itself a sample. Reshuffle the same trade
 * P&amp;Ls into thousands of random sequences and you get the distribution
 * of drawdowns and terminal wealth the strategy's own trades imply. The
 * question then stops being "the backtest drew down 18%" and
 * becomes "the 95th-percentile drawdown of my trade set is 31%, so plan
 * for 31, not 18".
 *
 * <p>What reshuffling holds fixed and what it breaks: it preserves the
 * multiset of trade outcomes (win rate, average win/loss, profit factor
 * are invariant) and destroys their order, so it isolates
 * "path/sequencing risk" from "edge". Its blind spot: it assumes trades
 * are exchangeable, so it understates risk for a strategy whose losses
 * cluster (serial correlation, regime dependence). A martingale that
 * wins small and loses catastrophically looks tamer reshuffled than it
 * is. For serially-correlated paths use {@link BlockBootstrap} on the
 * return series; this class is the per-trade complement, and the two
 * disagreeing is itself the signal that your trades are not
 * independent.</p>
 *
 * <p>The path compounds each trade's {@link Trade#returnPct()} (return on
 * the capital committed at entry): wealth {@code W_k = Π_{j≤k}(1 + r_j)}
 * from 1. Reshuffling currency P&amp;L instead would be wrong for an
 * all-in compounding book, where a trade's currency P&amp;L depends on the
 * equity at the time it was taken. Drawdowns are fractions of the running
 * peak, terminal values are total returns. Percentiles come from
 * {@code MathUtils.percentile}. Deterministic given the seed.</p>
 */
public final class MonteCarloTradeShuffle {

    /**
     * @param medianMaxDrawdown    50th-percentile max drawdown across shuffles
     *                             (fraction of the running peak)
     * @param p95MaxDrawdown       95th-percentile max drawdown, the plan-for number
     * @param p99MaxDrawdown       99th-percentile max drawdown, the tail
     * @param medianTerminalPnl    50th-percentile terminal total return
     *                             (order-invariant under compounding)
     * @param probLoss             fraction of shuffles ending below the start
     * @param actualMaxDrawdown    the observed (unshuffled) path's max drawdown
     * @param actualDrawdownPct    where the observed drawdown sits in the
     *                             shuffle distribution (0..1); high = the
     *                             real order was unusually painful, a hint
     *                             of loss clustering
     */
    public record Result(double medianMaxDrawdown, double p95MaxDrawdown, double p99MaxDrawdown,
                         double medianTerminalPnl, double probLoss,
                         double actualMaxDrawdown, double actualDrawdownPct) {
    }

    private MonteCarloTradeShuffle() {
    }

    /**
     * @param trades   the strategy's completed trades, at least 2
     * @param shuffles number of shuffles, at least 100
     * @param seed     random seed
     */
    public static Result analyze(List<Trade> trades, int shuffles, long seed) {
        int n = trades.size();
        if (n < 2) {
            throw new IllegalArgumentException("need >= 2 trades, got " + n);
        }
        if (shuffles < 100) {
            throw new IllegalArgumentException("need >= 100 shuffles, got " + shuffles);
        }
        double[] pnls = new double[n];
        for (int i = 0; i < n; i++) {
            double p = trades.get(i).returnPct();
            if (!Double.isFinite(p) || p < -1) {
                throw new IllegalArgumentException("invalid trade return at " + i + ": " + p);
            }
            pnls[i] = p;
        }

        double actualDd = maxDrawdown(pnls);
        double[] dd = new double[shuffles];
        double[] terminal = new double[shuffles];
        int losses = 0;
        int actualAtOrBelow = 0;
        SplittableRandom rnd = new SplittableRandom(seed);
        double[] work = new double[n];
        for (int s = 0; s < shuffles; s++) {
            System.arraycopy(pnls, 0, work, 0, n);
            // Fisher-Yates.
            for (int i = n - 1; i > 0; i--) {
                int j = rnd.nextInt(i + 1);
                double tmp = work[i];
                work[i] = work[j];
                work[j] = tmp;
            }
            dd[s] = maxDrawdown(work);
            double wealth = 1;
            for (double p : work) {
                wealth *= 1 + p;
            }
            terminal[s] = wealth - 1;
            if (wealth < 1) {
                losses++;
            }
            if (dd[s] <= actualDd) {
                actualAtOrBelow++;
            }
        }
        return new Result(
                MathUtils.percentile(dd, 0.50),
                MathUtils.percentile(dd, 0.95),
                MathUtils.percentile(dd, 0.99),
                MathUtils.percentile(terminal, 0.50),
                (double) losses / shuffles,
                actualDd,
                (double) actualAtOrBelow / shuffles);
    }

    /** Max peak-to-trough fractional drop of the compounded wealth path (in [0, 1]). */
    private static double maxDrawdown(double[] returns) {
        double wealth = 1, peak = 1, maxDd = 0;
        for (double r : returns) {
            wealth *= 1 + r;
            peak = Math.max(peak, wealth);
            maxDd = Math.max(maxDd, 1 - wealth / peak);
        }
        return maxDd;
    }
}
