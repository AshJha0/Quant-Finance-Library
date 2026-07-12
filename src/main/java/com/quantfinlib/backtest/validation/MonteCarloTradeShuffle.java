package com.quantfinlib.backtest.validation;

import com.quantfinlib.backtest.Trade;
import com.quantfinlib.util.MathUtils;

import java.util.List;
import java.util.SplittableRandom;

/**
 * MONTE CARLO trade reshuffling — the answer to "was my equity curve's
 * SHAPE luck?". A backtest reports one path: this strategy's trades in
 * the order they happened, producing one max drawdown and one terminal
 * equity. But the ORDER is itself a sample. Reshuffle the same trade
 * P&amp;Ls into thousands of random sequences and you get the distribution
 * of drawdowns and terminal wealth the strategy's OWN trades imply — and
 * the honest question stops being "the backtest drew down 18%" and
 * becomes "the 95th-percentile drawdown of my trade set is 31%, so plan
 * for 31, not 18".
 *
 * <p>What reshuffling holds fixed and what it breaks: it preserves the
 * MULTISET of trade outcomes (win rate, average win/loss, profit factor
 * are invariant) and destroys their ORDER — so it isolates
 * "path/sequencing risk" from "edge". Its blind spot, stated plainly:
 * it assumes trades are exchangeable, so it UNDERSTATES risk for a
 * strategy whose losses cluster (serial correlation, regime dependence)
 * — a martingale that wins small and loses catastrophically looks
 * tamer reshuffled than it is. For serially-correlated PATHS use
 * {@link BlockBootstrap} on the return series; this class is the
 * per-trade complement, and the two disagreeing is itself the signal
 * that your trades are not independent.</p>
 *
 * <p>Drawdown is computed on the cumulative P&amp;L path (additive, so no
 * starting-capital assumption); the percentiles come from
 * {@code MathUtils.percentile}. Deterministic given the seed. Research
 * lane.</p>
 */
public final class MonteCarloTradeShuffle {

    /**
     * @param medianMaxDrawdown    50th-percentile max drawdown across shuffles (P&amp;L units)
     * @param p95MaxDrawdown       95th-percentile max drawdown — the plan-for number
     * @param p99MaxDrawdown       99th-percentile max drawdown — the tail
     * @param medianTerminalPnl    50th-percentile terminal cumulative P&amp;L
     * @param probLoss             fraction of shuffles ending below zero
     * @param actualMaxDrawdown    the observed (unshuffled) path's max drawdown
     * @param actualDrawdownPct    where the observed drawdown sits in the
     *                             shuffle distribution (0..1); high = the
     *                             real order was UNUSUALLY painful, a hint
     *                             of loss clustering
     */
    public record Result(double medianMaxDrawdown, double p95MaxDrawdown, double p99MaxDrawdown,
                         double medianTerminalPnl, double probLoss,
                         double actualMaxDrawdown, double actualDrawdownPct) {
    }

    private MonteCarloTradeShuffle() {
    }

    /** @param trades the strategy's completed trades, &ge; 2; @param shuffles &ge; 100 */
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
            double p = trades.get(i).pnl();
            if (!Double.isFinite(p)) {
                throw new IllegalArgumentException("non-finite trade pnl at " + i);
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
            double sum = 0;
            for (double p : work) {
                sum += p;
            }
            terminal[s] = sum;
            if (sum < 0) {
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

    /** Max peak-to-trough drop on the cumulative-P&amp;L path (>= 0). */
    private static double maxDrawdown(double[] pnls) {
        double cum = 0, peak = 0, maxDd = 0;
        for (double p : pnls) {
            cum += p;
            peak = Math.max(peak, cum);
            maxDd = Math.max(maxDd, peak - cum);
        }
        return maxDd;
    }
}
