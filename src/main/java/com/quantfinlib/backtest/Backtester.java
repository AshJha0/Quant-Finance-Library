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
        strategy.init(series);
        int n = series.size();
        double[] equity = new double[n];
        List<Trade> trades = new ArrayList<>();

        double stopLoss = strategy.stopLossPct() > 0 ? strategy.stopLossPct() : config.stopLossPct();
        double takeProfit = strategy.takeProfitPct() > 0 ? strategy.takeProfitPct() : config.takeProfitPct();

        double cash = config.initialCapital();
        double qty = 0;
        double entryPrice = 0;
        double entryCost = 0;
        int entryIndex = -1;

        for (int i = 0; i < n; i++) {
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

            equity[i] = cash + qty * close;
        }

        // 3. Force-close any open position at the final bar.
        if (qty > 0) {
            double fill = series.close(n - 1) * (1 - config.slippageRate());
            cash = closePosition(trades, series, config, qty, entryPrice, entryCost,
                    entryIndex, n - 1, fill, Trade.REASON_END_OF_DATA, cash);
            equity[n - 1] = cash;
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
