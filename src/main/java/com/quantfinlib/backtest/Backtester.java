package com.quantfinlib.backtest;

import com.quantfinlib.core.BarSeries;

import java.util.ArrayList;
import java.util.List;

/**
 * Event-driven, single-instrument, long-only backtesting engine.
 *
 * <p>Execution model: signals fill at the bar close (adjusted for slippage);
 * stop-loss / take-profit levels are evaluated intrabar against the bar's
 * low/high on bars after entry, with gap-aware fills (a gap through the level
 * fills at the open). Commission is charged on both entry and exit notional.</p>
 */
public final class Backtester {

    private Backtester() {
    }

    public static BacktestResult run(TradingStrategy strategy, BarSeries series, BacktestConfig config) {
        return run(strategy, series, config, 0);
    }

    /**
     * Variant with a WARM-UP prefix: indicators are initialized over the
     * whole series, but no signal is acted on (and no equity recorded)
     * before {@code tradeFrom}. This is how walk-forward analysis avoids the
     * cold-start bias — evaluating a fold on a bare test slice re-computes
     * every indicator from scratch, silently forcing HOLD through the first
     * {@code lookback} bars of <i>every</i> fold; feeding the preceding bars
     * as warm-up (they are the past — no look-ahead) lets the strategy enter
     * the test window with warm indicators, the way it would trade live.
     * The returned equity curve covers {@code [tradeFrom, n)} only.
     *
     * <p>Scope of the warm-up: it warms whatever {@link TradingStrategy#init}
     * precomputes over the series (all shipped strategies). {@code onBar} is
     * NOT called for warm-up bars, so a strategy that accumulates state
     * inside {@code onBar} still starts cold at {@code tradeFrom}.</p>
     */
    public static BacktestResult run(TradingStrategy strategy, BarSeries series,
                                     BacktestConfig config, int tradeFrom) {
        int n = series.size();
        if (tradeFrom < 0 || tradeFrom >= n) {
            throw new IllegalArgumentException(
                    "tradeFrom must be in [0, " + n + "), got " + tradeFrom);
        }
        strategy.init(series);
        double[] equity = new double[n - tradeFrom];
        List<Trade> trades = new ArrayList<>();

        double stopLoss = strategy.stopLossPct() > 0 ? strategy.stopLossPct() : config.stopLossPct();
        double takeProfit = strategy.takeProfitPct() > 0 ? strategy.takeProfitPct() : config.takeProfitPct();

        double cash = config.initialCapital();
        double qty = 0;
        double entryPrice = 0;
        double entryCost = 0;
        int entryIndex = -1;

        for (int i = tradeFrom; i < n; i++) {
            // 1. Intrabar risk exits (only on bars after the entry bar).
            if (qty > 0 && i > entryIndex) {
                if (stopLoss > 0) {
                    double stopPrice = entryPrice * (1 - stopLoss);
                    if (series.low(i) <= stopPrice) {
                        double fill = Math.min(stopPrice, series.open(i));
                        cash = closePosition(trades, series, config, qty, entryPrice, entryCost,
                                entryIndex, i, fill, Trade.REASON_STOP_LOSS, cash);
                        qty = 0;
                    }
                }
                if (qty > 0 && takeProfit > 0) {
                    double targetPrice = entryPrice * (1 + takeProfit);
                    if (series.high(i) >= targetPrice) {
                        double fill = Math.max(targetPrice, series.open(i));
                        cash = closePosition(trades, series, config, qty, entryPrice, entryCost,
                                entryIndex, i, fill, Trade.REASON_TAKE_PROFIT, cash);
                        qty = 0;
                    }
                }
            }

            // 2. Strategy signal at the close.
            Signal sig = strategy.onBar(i);
            double close = series.close(i);
            if (sig == Signal.BUY && qty == 0 && cash > 0) {
                double fill = close * (1 + config.slippageRate());
                double fee = cash * config.commissionRate();
                qty = (cash - fee) / fill;
                entryPrice = fill;
                entryCost = cash;
                entryIndex = i;
                cash = 0;
            } else if (sig == Signal.SELL && qty > 0) {
                double fill = close * (1 - config.slippageRate());
                cash = closePosition(trades, series, config, qty, entryPrice, entryCost,
                        entryIndex, i, fill, Trade.REASON_SIGNAL, cash);
                qty = 0;
            }

            equity[i - tradeFrom] = cash + qty * close;
        }

        // 3. Force-close any open position at the final bar.
        if (qty > 0) {
            double fill = series.close(n - 1) * (1 - config.slippageRate());
            cash = closePosition(trades, series, config, qty, entryPrice, entryCost,
                    entryIndex, n - 1, fill, Trade.REASON_END_OF_DATA, cash);
            equity[equity.length - 1] = cash;
        }

        return new BacktestResult(strategy.name(), series.symbol(), equity, trades, config.periodsPerYear());
    }

    private static double closePosition(List<Trade> trades, BarSeries series, BacktestConfig config,
                                        double qty, double entryPrice, double entryCost,
                                        int entryIndex, int exitIndex, double fillPrice,
                                        String reason, double cash) {
        double proceeds = qty * fillPrice;
        double fee = proceeds * config.commissionRate();
        double net = proceeds - fee;
        double pnl = net - entryCost;
        trades.add(new Trade(series.symbol(), entryIndex, exitIndex,
                series.timestamp(entryIndex), series.timestamp(exitIndex),
                entryPrice, fillPrice, qty, pnl, entryCost == 0 ? 0 : pnl / entryCost, reason));
        return cash + net;
    }
}
