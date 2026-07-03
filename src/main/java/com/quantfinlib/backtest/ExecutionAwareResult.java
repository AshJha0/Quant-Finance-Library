package com.quantfinlib.backtest;

import com.quantfinlib.core.BarSeries;
import com.quantfinlib.microstructure.Execution;
import com.quantfinlib.microstructure.TransactionCostAnalyzer;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of an execution-aware backtest: the standard {@link BacktestResult}
 * (equity curve, trades, performance metrics) plus the full parent-order /
 * child-fill history, with one-call TCA per parent order.
 */
public final class ExecutionAwareResult {

    private final BacktestResult backtest;
    private final List<ParentOrder> parentOrders;
    private final BarSeries series;

    ExecutionAwareResult(BacktestResult backtest, List<ParentOrder> parentOrders, BarSeries series) {
        this.backtest = backtest;
        this.parentOrders = List.copyOf(parentOrders);
        this.series = series;
    }

    public BacktestResult backtest() {
        return backtest;
    }

    public List<ParentOrder> parentOrders() {
        return parentOrders;
    }

    /** Every child fill of the run, in execution order. */
    public List<Execution> allFills() {
        List<Execution> out = new ArrayList<>();
        for (ParentOrder p : parentOrders) {
            out.addAll(p.fills());
        }
        return out;
    }

    /**
     * Transaction cost analysis for one parent order: arrival mid = close at
     * the signal bar, market VWAP = volume-weighted close over the fill
     * interval, per-fill mid = close of the fill bar.
     *
     * @throws IllegalArgumentException if the parent has no fills
     */
    public TransactionCostAnalyzer.TcaReport tca(ParentOrder parent) {
        int n = parent.fills().size();
        if (n == 0) {
            throw new IllegalArgumentException("parent order has no fills");
        }
        double[] mids = new double[n];
        for (int i = 0; i < n; i++) {
            mids[i] = series.close(parent.fillBarIndices()[i]);
        }
        int first = parent.fillBarIndices()[0];
        int last = parent.fillBarIndices()[n - 1];
        double pv = 0, vol = 0;
        for (int i = first; i <= last; i++) {
            pv += series.close(i) * series.volume(i);
            vol += series.volume(i);
        }
        double marketVwap = vol == 0 ? parent.arrivalPrice() : pv / vol;
        return TransactionCostAnalyzer.analyze(parent.fills(), parent.arrivalPrice(), marketVwap, mids);
    }
}
