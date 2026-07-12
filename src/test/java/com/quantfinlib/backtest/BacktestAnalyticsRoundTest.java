package com.quantfinlib.backtest;

import com.quantfinlib.backtest.validation.MonteCarloTradeShuffle;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hand-pinned tests for the new backtest analytics: trade-level
 * statistics and Monte Carlo trade reshuffling.
 */
class BacktestAnalyticsRoundTest {

    private static Trade trade(double pnl, int bars) {
        return new Trade("X", 0, bars, 0L, bars, 100, 100 + pnl, 1, pnl,
                pnl / 100, Trade.REASON_SIGNAL);
    }

    // ------------------------------------------------------------ TradeAnalytics

    @Test
    void tradeStatisticsMatchHandArithmetic() {
        // 3 wins (+100,+50,+50 over 2/4/6 bars), 2 losses (-40,-60 over 8/10).
        List<Trade> trades = List.of(
                trade(100, 2), trade(-40, 8), trade(50, 4), trade(-60, 10), trade(50, 6));
        TradeAnalytics.Result r = TradeAnalytics.analyze(trades);

        assertEquals(5, r.count());
        assertEquals(0.6, r.winRate(), 1e-12);
        assertEquals(200.0 / 3, r.avgWin(), 1e-12);           // 200/3
        assertEquals(50.0, r.avgLoss(), 1e-12);               // 100/2
        assertEquals(0.6 * (200.0 / 3) - 0.4 * 50, r.expectancy(), 1e-12); // 40 - 20 = 20
        assertEquals((200.0 / 3) / 50.0, r.payoffRatio(), 1e-12);
        // Kelly = W - (1-W)/R = 0.6 - 0.4/(1.333) = 0.3.
        assertEquals(0.6 - 0.4 / ((200.0 / 3) / 50.0), r.kellyFraction(), 1e-12);
        // Winners held 2,4,6 -> avg 4; losers 8,10 -> avg 9.
        assertEquals(4.0, r.avgBarsHeldWinners(), 1e-12);
        assertEquals(9.0, r.avgBarsHeldLosers(), 1e-12);
    }

    @Test
    void streaksAndScratchTradeHandling() {
        // W W L L L W : max win streak 2, max loss streak 3; the scratch
        // trade in the middle breaks both streaks.
        List<Trade> trades = List.of(
                trade(10, 1), trade(10, 1), trade(-5, 1), trade(-5, 1), trade(-5, 1),
                trade(0, 1), trade(10, 1));
        TradeAnalytics.Result r = TradeAnalytics.analyze(trades);
        assertEquals(2, r.maxWinStreak());
        assertEquals(3, r.maxLossStreak());
        // A scratch trade is neither a win nor a loss.
        assertEquals(3.0 / 7, r.winRate(), 1e-12);
    }

    @Test
    void noLosersGivesInfinitePayoffAndFullKelly() {
        List<Trade> trades = List.of(trade(10, 1), trade(20, 1), trade(5, 1));
        TradeAnalytics.Result r = TradeAnalytics.analyze(trades);
        assertEquals(Double.POSITIVE_INFINITY, r.payoffRatio());
        assertEquals(1.0, r.kellyFraction(), 0.0, "no losers -> bet everything (the over-fit tell)");
        assertThrows(IllegalArgumentException.class, () -> TradeAnalytics.analyze(List.of()));
    }

    // ---------------------------------------------------- MonteCarloTradeShuffle

    @Test
    void reshuffleTerminalPnlIsOrderInvariantButDrawdownIsNot() {
        // Terminal P&L is the sum, identical in every ordering.
        List<Trade> trades = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            trades.add(trade(i % 2 == 0 ? 30 : -20, 1)); // net +100 over 20 trades
        }
        MonteCarloTradeShuffle.Result r = MonteCarloTradeShuffle.analyze(trades, 2000, 42);
        assertEquals(100.0, r.medianTerminalPnl(), 1e-9, "sum is order-invariant");
        assertEquals(0.0, r.probLoss(), 0.0, "always net positive");
        // Drawdown IS order-dependent, so the distribution has spread.
        assertTrue(r.p95MaxDrawdown() >= r.medianMaxDrawdown());
        assertTrue(r.p99MaxDrawdown() >= r.p95MaxDrawdown());
        assertTrue(r.medianMaxDrawdown() > 0);
        assertTrue(r.actualDrawdownPct() >= 0 && r.actualDrawdownPct() <= 1);
    }

    @Test
    void frontLoadedLossesRankAsAnUnusuallyPainfulActualOrdering() {
        // All losses first, then all wins: the worst possible drawdown
        // ordering, so the actual path should sit near the top of the
        // shuffle distribution.
        List<Trade> trades = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            trades.add(trade(-20, 1));
        }
        for (int i = 0; i < 10; i++) {
            trades.add(trade(30, 1));
        }
        MonteCarloTradeShuffle.Result r = MonteCarloTradeShuffle.analyze(trades, 2000, 7);
        assertEquals(200, r.actualMaxDrawdown(), 1e-9); // 10 * -20
        assertTrue(r.actualDrawdownPct() > 0.9,
                "worst-case ordering must rank in the tail: " + r.actualDrawdownPct());
    }

    @Test
    void shuffleGates() {
        List<Trade> one = List.of(trade(10, 1));
        assertThrows(IllegalArgumentException.class,
                () -> MonteCarloTradeShuffle.analyze(one, 1000, 1));
        List<Trade> two = List.of(trade(10, 1), trade(-5, 1));
        assertThrows(IllegalArgumentException.class,
                () -> MonteCarloTradeShuffle.analyze(two, 50, 1));
    }
}
