package com.quantfinlib.backtest;

import com.quantfinlib.core.BarSeries;

import java.util.ArrayList;
import java.util.List;

/**
 * Event-driven, single-instrument, long-only backtesting engine.
 *
 * <p><b>Fills.</b> The strategy decides at bar {@code i}'s close (its
 * indicators include {@code close(i)}). The order fills according to
 * {@link BacktestConfig#fillPolicy()}: at {@code open(i+1)} by default, at
 * {@code close(i+1)}, or at {@code close(i)} (the optimistic zero-latency
 * mode). Fills are adjusted by {@code slippageRate} against the trader and
 * commission is charged on entry and exit notional. A signal on the last
 * bar under a next-bar policy has nothing to fill against and is dropped.</p>
 *
 * <p><b>Stops.</b> Stop-loss and take-profit levels are checked intrabar
 * against the bar's low and high, from the first bar whose whole range
 * lies after the entry: the entry bar itself for a next-open entry, the
 * following bar for close entries. A gap through the level fills at the
 * open. Stop fills also pay {@code slippageRate}: a stop is a market order
 * and fills worse than its trigger in a fast market. When both levels lie
 * inside one bar's range the stop wins, since the bar data cannot say which
 * was touched first and assuming the loss is the conservative reading.</p>
 *
 * <p><b>Order of events on bar i.</b> (1) a pending next-open order fills
 * at {@code open(i)}; (2) stops are checked; (3) a pending next-close order
 * fills at {@code close(i)}; (4) the strategy is asked for a signal via
 * {@link TradingStrategy#onBar(int, double)} with the quantity now held;
 * (5) under {@code CLOSE} the signal fills immediately, otherwise it waits.
 * A pending order is dropped when it no longer applies (a stop already
 * closed the position, or the book is already in the requested state).</p>
 *
 * <p><b>Segments.</b> {@link #runSegment} runs over a window with a
 * starting {@link State} and returns the end state instead of liquidating,
 * which is how {@code WalkForwardAnalyzer} carries a position across fold
 * boundaries without a forced exit and re-entry per fold.</p>
 */
public final class Backtester {

    /**
     * Book state carried between segments.
     *
     * @param cash          cash held
     * @param quantity      shares held (0 = flat)
     * @param entryPrice    fill price of the open position (stops key off it)
     * @param entryCost     cash committed at entry
     * @param entryIndex    absolute bar index of the entry
     * @param entryTime     timestamp of the entry bar
     * @param pending       signal decided on the last bar and not yet filled
     */
    public record State(double cash, double quantity, double entryPrice, double entryCost,
                        int entryIndex, long entryTime, Signal pending) {

        /** Flat book with {@code capital} in cash. */
        public static State flat(double capital) {
            return new State(capital, 0, 0, 0, -1, 0, Signal.HOLD);
        }
    }

    /** A segment's result and the state it ended in. */
    public record Segment(BacktestResult result, State endState) {
    }

    private Backtester() {
    }

    public static BacktestResult run(TradingStrategy strategy, BarSeries series, BacktestConfig config) {
        return run(strategy, series, config, 0);
    }

    /**
     * Variant with a warm-up prefix: indicators are initialised over the
     * whole series, but no signal is acted on (and no equity recorded)
     * before {@code tradeFrom}. Walk-forward analysis uses this to avoid
     * re-computing every indicator cold on each test window. The returned
     * equity curve covers {@code [tradeFrom, n)}. Any position open at the
     * last bar is closed there at {@code close(n−1)} ({@link Trade#REASON_END_OF_DATA}).
     *
     * <p>The warm-up only warms what {@link TradingStrategy#init} precomputes
     * (all shipped strategies); {@code onBar} is not called for warm-up bars.</p>
     */
    public static BacktestResult run(TradingStrategy strategy, BarSeries series,
                                     BacktestConfig config, int tradeFrom) {
        return runSegment(strategy, series, config, tradeFrom,
                State.flat(config.initialCapital()), 0, true).result();
    }

    /**
     * Runs bars {@code [tradeFrom, n)} of {@code series} starting from
     * {@code start}. Trade indices are reported as {@code local index +
     * indexOffset}, so a caller running on a slice of a longer series passes
     * the slice's starting index and gets indices into the full series.
     * {@code start.entryIndex()} is absolute in the same sense.
     *
     * @param liquidateAtEnd close any open position at {@code close(n−1)}
     *                       (as {@link #run} does); otherwise carry it in the
     *                       returned state
     */
    public static Segment runSegment(TradingStrategy strategy, BarSeries series,
                                     BacktestConfig config, int tradeFrom, State start,
                                     int indexOffset, boolean liquidateAtEnd) {
        int n = series.size();
        if (tradeFrom < 0 || tradeFrom >= n) {
            throw new IllegalArgumentException(
                    "tradeFrom must be in [0, " + n + "), got " + tradeFrom);
        }
        strategy.init(series);
        Engine e = new Engine(strategy, series, config, start, indexOffset);
        double[] equity = new double[n - tradeFrom];
        for (int i = tradeFrom; i < n; i++) {
            e.bar(i);
            equity[i - tradeFrom] = e.cash + e.qty * series.close(i);
        }
        if (liquidateAtEnd && e.qty > 0) {
            double fill = series.close(n - 1) * (1 - config.slippageRate());
            e.close(n - 1, fill, Trade.REASON_END_OF_DATA);
            equity[equity.length - 1] = e.cash;
        }
        Signal pending = liquidateAtEnd ? Signal.HOLD : e.pending;
        State end = new State(e.cash, e.qty, e.entryPrice, e.entryCost, e.entryIndex,
                e.entryTime, pending);
        return new Segment(new BacktestResult(strategy.name(), series.symbol(), equity, e.trades,
                config.periodsPerYear()), end);
    }

    /** Mutable book for one run. Indices are local to the series; entryIndex is absolute. */
    private static final class Engine {
        final TradingStrategy strategy;
        final BarSeries series;
        final BacktestConfig config;
        final int offset;
        final double stopLoss;
        final double takeProfit;
        final List<Trade> trades = new ArrayList<>();
        double cash;
        double qty;
        double entryPrice;
        double entryCost;
        int entryIndex;      // absolute
        long entryTime;
        boolean entryAtOpen; // stops may trigger on the entry bar itself
        Signal pending;

        Engine(TradingStrategy strategy, BarSeries series, BacktestConfig config, State s,
               int offset) {
            this.strategy = strategy;
            this.series = series;
            this.config = config;
            this.offset = offset;
            this.stopLoss = strategy.stopLossPct() > 0 ? strategy.stopLossPct() : config.stopLossPct();
            this.takeProfit = strategy.takeProfitPct() > 0
                    ? strategy.takeProfitPct() : config.takeProfitPct();
            this.cash = s.cash();
            this.qty = s.quantity();
            this.entryPrice = s.entryPrice();
            this.entryCost = s.entryCost();
            this.entryIndex = s.entryIndex();
            this.entryTime = s.entryTime();
            this.pending = s.pending() == null ? Signal.HOLD : s.pending();
        }

        void bar(int i) {
            FillPolicy fp = config.fillPolicy();
            if (fp == FillPolicy.NEXT_OPEN) {
                fillPending(i, series.open(i), true);
            }
            checkStops(i);
            if (fp == FillPolicy.NEXT_CLOSE) {
                fillPending(i, series.close(i), false);
            }
            Signal sig = strategy.onBar(i, qty);
            if (fp == FillPolicy.CLOSE) {
                pending = sig;
                fillPending(i, series.close(i), false);
            } else if (sig != Signal.HOLD) {
                pending = sig;
            }
        }

        void fillPending(int i, double price, boolean atOpen) {
            Signal sig = pending;
            pending = Signal.HOLD;
            if (sig == Signal.BUY && qty == 0 && cash > 0) {
                double fill = price * (1 + config.slippageRate());
                double fee = cash * config.commissionRate();
                qty = (cash - fee) / fill;
                entryPrice = fill;
                entryCost = cash;
                entryIndex = i + offset;
                entryTime = series.timestamp(i);
                entryAtOpen = atOpen;
                cash = 0;
            } else if (sig == Signal.SELL && qty > 0) {
                close(i, price * (1 - config.slippageRate()), Trade.REASON_SIGNAL);
            }
        }

        void checkStops(int i) {
            int local = entryIndex - offset;
            boolean eligible = qty > 0 && (i > local || (i == local && entryAtOpen));
            if (!eligible) {
                return;
            }
            if (stopLoss > 0) {
                double stopPrice = entryPrice * (1 - stopLoss);
                if (series.low(i) <= stopPrice) {
                    double trigger = Math.min(stopPrice, series.open(i));
                    close(i, trigger * (1 - config.slippageRate()), Trade.REASON_STOP_LOSS);
                    return;
                }
            }
            if (takeProfit > 0) {
                double targetPrice = entryPrice * (1 + takeProfit);
                if (series.high(i) >= targetPrice) {
                    double trigger = Math.max(targetPrice, series.open(i));
                    close(i, trigger * (1 - config.slippageRate()), Trade.REASON_TAKE_PROFIT);
                }
            }
        }

        void close(int i, double fillPrice, String reason) {
            double proceeds = qty * fillPrice;
            double fee = proceeds * config.commissionRate();
            double netProceeds = proceeds - fee;
            double pnl = netProceeds - entryCost;
            trades.add(new Trade(series.symbol(), entryIndex, i + offset,
                    entryTime, series.timestamp(i),
                    entryPrice, fillPrice, qty, pnl, entryCost == 0 ? 0 : pnl / entryCost, reason));
            cash += netProceeds;
            qty = 0;
            pending = Signal.HOLD;
        }
    }
}
