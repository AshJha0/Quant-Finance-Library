package com.quantfinlib.backtest;

import java.util.List;

/**
 * TRADE-LEVEL analytics — the numbers a discretionary reviewer asks for
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
 *   <li><b>payoff ratio</b> — avgWin / avgLoss: paired with win rate it
 *       IS the strategy's character. A 40% win rate needs a payoff above
 *       1.5 to have positive expectancy — the arithmetic that kills most
 *       "high win rate" systems whose few losses are huge;</li>
 *   <li><b>streaks</b> — the longest run of consecutive losers (and
 *       winners): the number that decides whether you can psychologically
 *       and financially SIT through the strategy. A positive-expectancy
 *       system with an 11-trade losing streak gets turned off by its
 *       owner at trade 8;</li>
 *   <li><b>Kelly fraction</b> — {@code W - (1-W)/R} for win rate W and
 *       payoff R: the growth-optimal bet size the trade record implies,
 *       and a reality check (a Kelly above ~0.25 usually means the
 *       sample is too small or the wins too lucky);</li>
 *   <li><b>hold times</b> — average bars held for winners vs losers: when
 *       losers are held far longer than winners, that is the
 *       disposition effect showing up in the tape.</li>
 * </ul>
 *
 * <p>All statistics are on realized {@link Trade} P&amp;L; a strategy with
 * no losing trades reports an infinite payoff ratio and Kelly clamped to
 * 1 (bet everything — which is exactly the over-fit warning you want).
 * Static, deterministic, research lane. Complements
 * {@link PerformanceAnalytics} (equity-curve metrics) and
 * {@code validation.MonteCarloTradeShuffle} (is the SEQUENCE luck?).</p>
 */
public final class TradeAnalytics {

    /**
     * @param count                 number of trades
     * @param winRate               fraction of strictly-positive trades
     * @param expectancy            average P&amp;L per trade
     * @param avgWin                mean P&amp;L of winning trades (0 if none)
     * @param avgLoss               mean magnitude of losing trades (0 if none)
     * @param payoffRatio           avgWin / avgLoss (+inf if no losers)
     * @param maxWinStreak          longest run of consecutive winners
     * @param maxLossStreak         longest run of consecutive losers
     * @param kellyFraction         W - (1-W)/R, clamped to [0, 1]
     * @param avgBarsHeldWinners    mean holding period of winners
     * @param avgBarsHeldLosers     mean holding period of losers
     */
    public record Result(int count, double winRate, double expectancy, double avgWin,
                         double avgLoss, double payoffRatio, int maxWinStreak, int maxLossStreak,
                         double kellyFraction, double avgBarsHeldWinners, double avgBarsHeldLosers) {
    }

    private TradeAnalytics() {
    }

    /** @param trades completed trades, non-empty */
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
        // Kelly = W - (1-W)/R, clamped: no losers -> bet everything (the
        // over-fit tell); non-positive edge -> zero.
        double kelly;
        if (avgLoss == 0) {
            kelly = 1.0;
        } else {
            kelly = winRate - (1 - winRate) / payoff;
            kelly = Math.max(0, Math.min(1, kelly));
        }
        double avgWinBars = wins > 0 ? (double) winBars / wins : 0;
        double avgLossBars = losers > 0 ? (double) lossBars / losers : 0;
        return new Result(n, winRate, expectancy, avgWin, avgLoss, payoff,
                maxWin, maxLoss, kelly, avgWinBars, avgLossBars);
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
