package com.quantfinlib.backtest;

import com.quantfinlib.core.BarSeries;
import com.quantfinlib.microstructure.Execution;
import com.quantfinlib.orderbook.Side;

import java.util.List;

/**
 * Baseline execution model: the full quantity fills at the bar close with
 * commission and slippage folded into the all-in price — equivalent to the
 * classic {@link Backtester} fill assumption.
 */
public final class InstantExecution implements ExecutionModel {

    private final double costRate;

    public InstantExecution(double commissionRate, double slippageRate) {
        this.costRate = commissionRate + slippageRate;
    }

    public static InstantExecution from(BacktestConfig config) {
        return new InstantExecution(config.commissionRate(), config.slippageRate());
    }

    @Override
    public List<Execution> execute(Side side, long requestedQty, BarSeries series, int index) {
        if (requestedQty <= 0) {
            return List.of();
        }
        double allIn = series.close(index) * (1 + side.sign() * costRate);
        return List.of(new Execution(series.symbol(), side, allIn, requestedQty,
                series.timestamp(index), "PRIMARY"));
    }
}
