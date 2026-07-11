package com.quantfinlib.backtest;

import com.quantfinlib.core.BarSeries;
import com.quantfinlib.microstructure.Execution;
import com.quantfinlib.orderbook.Side;

import java.util.ArrayList;
import java.util.List;

/**
 * Execution-aware backtesting engine: strategy signals create <b>parent
 * orders</b> that are worked through an {@link ExecutionModel} — routed by
 * {@link SorExecution}, sliced by {@link IcebergExecution}, or filled
 * instantly by {@link InstantExecution}. Fills can span multiple bars, the
 * position accumulates gradually, and every child fill is recorded so
 * execution cost (TCA) is measurable per parent order.
 *
 * <p>Semantics (long-only, single instrument, like {@link Backtester}):</p>
 * <ul>
 *   <li>BUY signal while flat → entry parent sized to available cash; worked
 *       from the signal bar until filled or superseded.</li>
 *   <li>SELL signal → cancels any unfilled entry remainder and works an exit
 *       parent for the whole position.</li>
 *   <li>Stop-loss / take-profit are evaluated intrabar against the
 *       <i>volume-weighted average entry price</i>; the triggered exit is
 *       worked through the execution model (a patient model exits slowly —
 *       that realism is the point).</li>
 *   <li>Any position left at the end of data is force-closed at the last
 *       close less the model's worst-case cost fraction — unconditional
 *       (it bypasses the model's fill logic) but not free.</li>
 * </ul>
 */
public final class ExecutionAwareBacktester {

    private static final class ParentState {
        final Side side;
        final int signalIndex;
        final double arrivalPrice;
        String reason;
        final List<Execution> fills = new ArrayList<>();
        final List<Integer> bars = new ArrayList<>();

        ParentState(Side side, int signalIndex, double arrivalPrice, String reason) {
            this.side = side;
            this.signalIndex = signalIndex;
            this.arrivalPrice = arrivalPrice;
            this.reason = reason;
        }

        ParentOrder toRecord() {
            int[] idx = new int[bars.size()];
            for (int i = 0; i < idx.length; i++) {
                idx[i] = bars.get(i);
            }
            return new ParentOrder(side, signalIndex, arrivalPrice, reason, List.copyOf(fills), idx);
        }
    }

    private final TradingStrategy strategy;
    private final BarSeries series;
    private final BacktestConfig config;
    private final ExecutionModel model;
    private final double stopLossPct;
    private final double takeProfitPct;

    private final List<Trade> trades = new ArrayList<>();
    private final List<ParentState> parents = new ArrayList<>();
    private double cash;
    private long position;
    private long pendingEntry;
    private long pendingExit;
    private double entryCost;      // all-in cash spent on the current round trip's entries
    private double exitProceeds;
    private long totalEntryQty;
    private int firstEntryBar = -1;
    private ParentState entry;
    private ParentState exit;

    private ExecutionAwareBacktester(TradingStrategy strategy, BarSeries series,
                                     BacktestConfig config, ExecutionModel model) {
        this.strategy = strategy;
        this.series = series;
        this.config = config;
        this.model = model;
        this.stopLossPct = strategy.stopLossPct() > 0 ? strategy.stopLossPct() : config.stopLossPct();
        this.takeProfitPct = strategy.takeProfitPct() > 0 ? strategy.takeProfitPct() : config.takeProfitPct();
    }

    public static ExecutionAwareResult run(TradingStrategy strategy, BarSeries series,
                                           BacktestConfig config, ExecutionModel model) {
        return new ExecutionAwareBacktester(strategy, series, config, model).execute();
    }

    private ExecutionAwareResult execute() {
        strategy.init(series);
        int n = series.size();
        double[] equity = new double[n];
        cash = config.initialCapital();

        for (int i = 0; i < n; i++) {
            // 1. Keep working whatever parent order is open.
            if (pendingExit > 0) {
                workExit(i);
            } else if (pendingEntry > 0) {
                workEntry(i);
            }
            // 2. Intrabar risk exits on the accumulated position.
            checkRiskExits(i);
            // 3. Strategy signal at the close.
            onSignal(strategy.onBar(i), i);

            equity[i] = cash + position * series.close(i);
        }
        forceClose(n - 1, equity);

        List<ParentOrder> parentRecords = new ArrayList<>(parents.size());
        for (ParentState p : parents) {
            parentRecords.add(p.toRecord());
        }
        BacktestResult result = new BacktestResult(strategy.name(), series.symbol(),
                equity, trades, config.periodsPerYear());
        return new ExecutionAwareResult(result, parentRecords, series);
    }

    // ------------------------------------------------------------------

    private void workEntry(int i) {
        // Cap the request by what cash can actually pay for, priced at the
        // model's own fill anchor (close for most models, the OPEN for
        // last-look) with its declared worst-case all-in cost on top — a
        // flat close-based 1% buffer overdraws cash the moment a model
        // charges more, or fills off a gapped open.
        double ref = model.referencePrice(series, i);
        long affordable = (long) (cash / (ref * (1 + model.worstCaseCostFraction())));
        long request = Math.min(pendingEntry, affordable);
        if (request <= 0) {
            return;
        }
        for (Execution f : model.execute(Side.BUY, request, series, i)) {
            double cost = f.notional();
            cash -= cost;
            entryCost += cost;
            position += f.quantity();
            totalEntryQty += f.quantity();
            pendingEntry -= f.quantity();
            entry.fills.add(f);
            entry.bars.add(i);
            if (firstEntryBar < 0) {
                firstEntryBar = i;
            }
        }
    }

    private void workExit(int i) {
        for (Execution f : model.execute(Side.SELL, pendingExit, series, i)) {
            double proceeds = f.notional();
            cash += proceeds;
            exitProceeds += proceeds;
            position -= f.quantity();
            pendingExit -= f.quantity();
            exit.fills.add(f);
            exit.bars.add(i);
        }
        if (position == 0 && pendingExit == 0) {
            closeRoundTrip(i);
        }
    }

    private void checkRiskExits(int i) {
        if (position <= 0 || pendingExit > 0 || firstEntryBar < 0 || i <= firstEntryBar) {
            return;
        }
        double avgEntry = entryCost / totalEntryQty;
        String reason = null;
        if (stopLossPct > 0 && series.low(i) <= avgEntry * (1 - stopLossPct)) {
            reason = Trade.REASON_STOP_LOSS;
        } else if (takeProfitPct > 0 && series.high(i) >= avgEntry * (1 + takeProfitPct)) {
            reason = Trade.REASON_TAKE_PROFIT;
        }
        if (reason != null) {
            startExit(i, reason);
            workExit(i);
        }
    }

    private void onSignal(Signal signal, int i) {
        if (signal == Signal.BUY && position == 0 && pendingEntry == 0 && pendingExit == 0) {
            double close = series.close(i);
            long target = (long) (cash / (close * (1 + model.worstCaseCostFraction())));
            if (target <= 0) {
                return;
            }
            entry = new ParentState(Side.BUY, i, close, ParentOrder.REASON_ENTRY);
            parents.add(entry);
            entryCost = 0;
            exitProceeds = 0;
            totalEntryQty = 0;
            firstEntryBar = -1;
            pendingEntry = target;
            model.onParentOrder(Side.BUY, target, i);
            workEntry(i);
        } else if (signal == Signal.SELL && pendingExit == 0 && (position > 0 || pendingEntry > 0)) {
            pendingEntry = 0;   // cancel unfilled entry remainder
            if (position > 0) {
                startExit(i, Trade.REASON_SIGNAL);
                workExit(i);
            } else {
                entry = null;   // nothing filled; abandon the parent
            }
        }
    }

    private void startExit(int i, String reason) {
        pendingEntry = 0;
        exit = new ParentState(Side.SELL, i, series.close(i), reason);
        parents.add(exit);
        pendingExit = position;
        model.onParentOrder(Side.SELL, position, i);
    }

    private void closeRoundTrip(int i) {
        double avgEntry = entryCost / totalEntryQty;
        double avgExit = exitProceeds / totalEntryQty;
        double pnl = exitProceeds - entryCost;
        trades.add(new Trade(series.symbol(), firstEntryBar, i,
                series.timestamp(firstEntryBar), series.timestamp(i),
                avgEntry, avgExit, totalEntryQty, pnl,
                entryCost == 0 ? 0 : pnl / entryCost, exit.reason));
        entry = null;
        exit = null;
        firstEntryBar = -1;
        entryCost = 0;
        exitProceeds = 0;
        totalEntryQty = 0;
    }

    private void forceClose(int last, double[] equity) {
        pendingEntry = 0;
        if (position <= 0) {
            return;
        }
        if (exit == null) {
            exit = new ParentState(Side.SELL, last, series.close(last), Trade.REASON_END_OF_DATA);
            parents.add(exit);
        }
        // Even a forced close pays to trade: charge the model's worst-case
        // cost rather than exiting for free — a run that ends holding must
        // not get its last round trip's exit cost waived.
        double px = series.close(last) * (1 - model.worstCaseCostFraction());
        Execution fill = new Execution(series.symbol(), Side.SELL, px, position,
                series.timestamp(last), "FORCED_CLOSE");
        exit.fills.add(fill);
        exit.bars.add(last);
        cash += px * position;
        exitProceeds += px * position;
        position = 0;
        pendingExit = 0;
        closeRoundTrip(last);
        equity[last] = cash;
    }
}
