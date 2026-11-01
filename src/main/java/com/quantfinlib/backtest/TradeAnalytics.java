package com.quantfinlib.backtest;

import java.util.List;

/**
 * Trade-level analytics — the numbers a discretionary reviewer asks for
 * that a Sharpe ratio hides. Two strategies with the same Sharpe can have
 * completely different trade signatures: one wins small and often with a
 * few large losses (a hidden short-gamma profile that blows up), the
 * other loses small and often with rare large wins (trend following, hard
 * to hold). These statistics expose the difference.
 *
 * <ul>
 *   <li><b>expectancy</b> — the average P&amp;L per trade,
 *       {@code winRate*avgWin - lossRate*avgLoss}: the single number that
 *       says whether the edge survives being averaged over every
 *       trade;</li>
 *   <li><b>payoff ratio</b>: avgWin / avgLoss. Paired with win rate it
 *       is the strategy's character. A 40% win rate needs a payoff above
 *       1.5 to have positive expectancy, the arithmetic that kills most
 *       "high win rate" systems whose few losses are huge;</li>
 *   <li><b>streaks</b> — the longest run of consecutive losers (and
 *       winners): the number that decides whether you can psychologically
 *       and financially sit through the strategy. A positive-expectancy
 *       system with an 11-trade losing streak gets turned off by its
 *       owner at trade 8;</li>
 *   <li><b>Kelly fraction</b>: the fraction {@code f ∈ [0, 1]} of equity
 *       to commit per trade that maximises the empirical log growth
 *       {@code Σ log(1 + f·rᵢ)} over the trades' {@link Trade#returnPct()}
 *       (Kelly 1956; Thorp 2006, Handbook of Asset and Liability
 *       Management, §2). The fixed-odds formula {@code W − (1−W)/R} on
 *       currency P&amp;L is not a sizing number for all-in trades of a
 *       compounding book, whose P&amp;L scale depends on equity at the
 *       time. A Kelly above ~0.25 usually means the sample is too small
 *       or the wins too lucky;</li>
 *   <li><b>hold times</b> — average bars held for winners vs losers: when
 *       losers are held far longer than winners, that is the
 *       disposition effect showing up in the tape.</li>
 * </ul>
 *
 * <p>Statistics other than Kelly are on realised {@link Trade} P&amp;L; a
 * strategy with no losing trades reports an infinite payoff ratio and a
 * Kelly of 1 (bet everything, which is the over-fit warning you want); a
 * record with no positive mean return reports Kelly 0.
 * Static, deterministic, research lane. Complements
 * {@link PerformanceAnalytics} (equity-curve metrics) and
 * {@code validation.MonteCarloTradeShuffle} (is the sequence luck?).</p>
 */
public final class TradeAnalytics {

    /**
     * Trade-level statistics.
     *
     * @param count                 number of trades
     * @param winRate               fraction of strictly-positive trades
     * @param expectancy            average P&amp;L per trade
     * @param avgWin                mean P&amp;L of winning trades (0 if none)
     * @param avgLoss               mean magnitude of losing trades (0 if none)
     * @param payoffRatio           avgWin / avgLoss (+inf if no losers)
     * @param maxWinStreak          longest run of consecutive winners
     * @param maxLossStreak         longest run of consecutive losers
     * @param kellyFraction         empirical growth-optimal fraction in [0, 1] (see class doc)
     * @param avgBarsHeldWinners    mean holding period of winners
     * @param avgBarsHeldLosers     mean holding period of losers
     */
    public record Result(int count, double winRate, double expectancy, double avgWin,
                         double avgLoss, double payoffRatio, int maxWinStreak, int maxLossStreak,
                         double kellyFraction, double avgBarsHeldWinners, double avgBarsHeldLosers) {
    }

    private TradeAnalytics() {
    }

    /**
     * Computes trade-level statistics.
     *
     * @param trades completed trades, non-empty
     */
    public static Result analyze(List<Trade> trades) {
        int n = trades.size();
        if (n == 0) {
            throw new IllegalArgumentException("need at least one trade");
        }
        int wins = 0;
        double winSum = 0, lossSum = 0;
        long winBars = 0, lossBars = 0;
        int maxWin = 0, maxLoss = 0, curWin = 0, curLoss = 0;
        for (Trade t : trades) {
            if (!Double.isFinite(t.pnl())) {
                throw new IllegalArgumentException("non-finite trade pnl");
            }
            if (t.pnl() > 0) {
                wins++;
                winSum += t.pnl();
                winBars += t.barsHeld();
                curWin++;
                curLoss = 0;
                maxWin = Math.max(maxWin, curWin);
            } else if (t.pnl() < 0) {
                lossSum += -t.pnl();
                lossBars += t.barsHeld();
                curLoss++;
                curWin = 0;
                maxLoss = Math.max(maxLoss, curLoss);
            } else {
                // Scratch trade breaks both streaks; belongs to neither leg.
                curWin = 0;
                curLoss = 0;
            }
        }
        int losers = countLosers(trades);
        double winRate = (double) wins / n;
        double avgWin = wins > 0 ? winSum / wins : 0;
        double avgLoss = losers > 0 ? lossSum / losers : 0;
        double lossRate = (double) losers / n;
        double expectancy = winRate * avgWin - lossRate * avgLoss;
        double payoff = avgLoss > 0 ? avgWin / avgLoss : Double.POSITIVE_INFINITY;
        double kelly = empiricalKelly(trades);
        double avgWinBars = wins > 0 ? (double) winBars / wins : 0;
        double avgLossBars = losers > 0 ? (double) lossBars / losers : 0;
        return new Result(n, winRate, expectancy, avgWin, avgLoss, payoff,
                maxWin, maxLoss, kelly, avgWinBars, avgLossBars);
    }

    /**
     * argmax over f in [0, 1] of Σ log(1 + f·rᵢ). The objective is concave,
     * so the root of its derivative {@code Σ rᵢ/(1 + f·rᵢ)} is found by
     * bisection: 0 when the derivative at 0 (the sum of returns) is not
     * positive, 1 when it is still positive at 1.
     */
    static double empiricalKelly(List<Trade> trades) {
        double[] r = new double[trades.size()];
        for (int i = 0; i < r.length; i++) {
            r[i] = trades.get(i).returnPct();
            if (!Double.isFinite(r[i]) || r[i] < -1) {
                throw new IllegalArgumentException("trade " + i + " has return " + r[i]);
            }
        }
        if (slope(r, 0) <= 0) {
            return 0;
        }
        if (slope(r, 1) > 0) {
            return 1;
        }
        double lo = 0;
        double hi = 1;
        for (int it = 0; it < 100 && hi - lo > 1e-15; it++) {
            double mid = (lo + hi) / 2;
            if (slope(r, mid) > 0) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return (lo + hi) / 2;
    }

    /** d/df Σ log(1 + f r); −∞ when a total-loss trade meets f = 1. */
    private static double slope(double[] r, double f) {
        double s = 0;
        for (double x : r) {
            double denom = 1 + f * x;
            if (denom <= 0) {
                return Double.NEGATIVE_INFINITY;
            }
            s += x / denom;
        }
        return s;
    }

    private static int countLosers(List<Trade> trades) {
        int losers = 0;
        for (Trade t : trades) {
            if (t.pnl() < 0) {
                losers++;
            }
        }
        return losers;
    }
}
