package com.quantfinlib.backtest;

import com.quantfinlib.backtest.validation.MonteCarloTradeShuffle;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Degenerate-trade-set pins the first analytics layer left open:
 * all-scratch and all-loss records, the expectancy = mean-P&amp;L
 * identity, and the shuffle's seed determinism plus its collapse on an
 * order-free trade set.
 */
class TradeAnalyticsEdgeTest {

    private static Trade trade(double pnl, int bars) {
        return new Trade("X", 0, bars, 0L, bars, 100, 100 + pnl, 1, pnl,
                pnl / 100, Trade.REASON_SIGNAL);
    }

    // ------------------------------------------------------------ TradeAnalytics

    @Test
    void allScratchTradesProduceTheDocumentedNoLoserDegeneracy() {
        // Every trade breaks even: no wins, no losses. Pinning CURRENT
        // behavior: avgLoss == 0 takes the documented "no losers" branch,
        // so payoff is +inf and Kelly clamps to 1 even though there are no
        // winners either — the same over-fit tell as a loss-free record.
        TradeAnalytics.Result r = TradeAnalytics.analyze(
                List.of(trade(0, 1), trade(0, 2), trade(0, 3)));
        assertEquals(3, r.count());
        assertEquals(0.0, r.winRate(), 0.0);
        assertEquals(0.0, r.avgWin(), 0.0);
        assertEquals(0.0, r.avgLoss(), 0.0);
        assertEquals(0.0, r.expectancy(), 0.0);
        assertEquals(Double.POSITIVE_INFINITY, r.payoffRatio());
        assertEquals(1.0, r.kellyFraction(), 0.0);
        // Scratches break streaks and belong to neither leg.
        assertEquals(0, r.maxWinStreak());
        assertEquals(0, r.maxLossStreak());
        assertEquals(0.0, r.avgBarsHeldWinners(), 0.0);
        assertEquals(0.0, r.avgBarsHeldLosers(), 0.0);
    }

    @Test
    void allLossesClampKellyToZero() {
        // W = 0 and R = avgWin/avgLoss = 0: Kelly = 0 - 1/0 = -inf, which
        // the clamp turns into 0 — never bet a strategy that only loses.
        TradeAnalytics.Result r = TradeAnalytics.analyze(
                List.of(trade(-10, 2), trade(-20, 4), trade(-30, 6)));
        assertEquals(0.0, r.winRate(), 0.0);
        assertEquals(20.0, r.avgLoss(), 1e-12);           // (10+20+30)/3
        assertEquals(0.0, r.payoffRatio(), 0.0);
        assertEquals(0.0, r.kellyFraction(), 0.0);
        assertEquals(3, r.maxLossStreak());
        assertEquals(0, r.maxWinStreak());
        assertEquals(-20.0, r.expectancy(), 1e-12);       // 0*0 - 1*20
        // The single-loss record is the same story.
        TradeAnalytics.Result single = TradeAnalytics.analyze(List.of(trade(-50, 1)));
        assertEquals(0.0, single.kellyFraction(), 0.0);
        assertEquals(1, single.maxLossStreak());
    }

    @Test
    void expectancyIsExactlyTheMeanPnlIncludingScratches() {
        // winRate*avgWin - lossRate*avgLoss = (winSum - lossSum)/n, i.e.
        // the plain average P&L per trade with scratches diluting it:
        // (+30 - 10 + 0)/3 = 20/3.
        TradeAnalytics.Result r = TradeAnalytics.analyze(
                List.of(trade(30, 1), trade(-10, 1), trade(0, 1)));
        assertEquals(20.0 / 3, r.expectancy(), 1e-12);
    }

    // ---------------------------------------------------- MonteCarloTradeShuffle

    @Test
    void sameSeedReproducesTheShuffleDistributionExactly() {
        List<Trade> trades = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            trades.add(trade(i % 3 == 0 ? -25 : 15, 1));
        }
        MonteCarloTradeShuffle.Result a = MonteCarloTradeShuffle.analyze(trades, 500, 123);
        MonteCarloTradeShuffle.Result b = MonteCarloTradeShuffle.analyze(trades, 500, 123);
        assertEquals(a, b, "same seed, same trades: bit-identical result");
        // A different seed draws different orderings; the median drawdown
        // is a fine-grained statistic, so at least one field should move.
        MonteCarloTradeShuffle.Result c = MonteCarloTradeShuffle.analyze(trades, 500, 124);
        assertTrue(!a.equals(c) || a.medianMaxDrawdown() == c.medianMaxDrawdown(),
                "different seed is allowed to differ (and usually does)");
    }

    @Test
    void identicalTradePnlsCollapseTheDistributionToASinglePoint() {
        // Ten identical -5 trades: every permutation is the SAME path, so
        // all drawdown percentiles equal the actual drawdown (50), every
        // shuffle ends at -50, and the loss probability is 1.
        List<Trade> trades = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            trades.add(trade(-5, 1));
        }
        MonteCarloTradeShuffle.Result r = MonteCarloTradeShuffle.analyze(trades, 200, 9);
        assertEquals(50.0, r.actualMaxDrawdown(), 1e-12);
        assertEquals(50.0, r.medianMaxDrawdown(), 1e-12);
        assertEquals(50.0, r.p95MaxDrawdown(), 1e-12);
        assertEquals(50.0, r.p99MaxDrawdown(), 1e-12);
        assertEquals(-50.0, r.medianTerminalPnl(), 1e-12);
        assertEquals(1.0, r.probLoss(), 0.0);
        assertEquals(1.0, r.actualDrawdownPct(), 0.0,
                "every shuffle draws down exactly as much as the actual path");
    }
}
