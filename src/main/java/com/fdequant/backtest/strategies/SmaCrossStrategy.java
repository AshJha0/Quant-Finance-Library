package com.fdequant.backtest.strategies;

import com.fdequant.backtest.Signal;
import com.fdequant.backtest.TradingStrategy;
import com.fdequant.core.BarSeries;
import com.fdequant.indicators.Indicators;

/** Golden/death cross: buy when the fast SMA crosses above the slow SMA, sell on the reverse cross. */
public final class SmaCrossStrategy implements TradingStrategy {

    private final int fastPeriod;
    private final int slowPeriod;
    private double[] fast;
    private double[] slow;

    public SmaCrossStrategy(int fastPeriod, int slowPeriod) {
        if (fastPeriod >= slowPeriod) {
            throw new IllegalArgumentException("fastPeriod must be < slowPeriod");
        }
        this.fastPeriod = fastPeriod;
        this.slowPeriod = slowPeriod;
    }

    @Override
    public String name() {
        return "SMA_CROSS(" + fastPeriod + "," + slowPeriod + ")";
    }

    @Override
    public void init(BarSeries series) {
        fast = Indicators.sma(series.closes(), fastPeriod);
        slow = Indicators.sma(series.closes(), slowPeriod);
    }

    @Override
    public Signal onBar(int i) {
        if (i < 1 || Double.isNaN(slow[i]) || Double.isNaN(slow[i - 1])) {
            return Signal.HOLD;
        }
        if (fast[i - 1] <= slow[i - 1] && fast[i] > slow[i]) {
            return Signal.BUY;
        }
        if (fast[i - 1] >= slow[i - 1] && fast[i] < slow[i]) {
            return Signal.SELL;
        }
        return Signal.HOLD;
    }
}
