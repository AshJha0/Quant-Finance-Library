package com.quantfinlib.backtest.strategies;

import com.quantfinlib.backtest.Signal;
import com.quantfinlib.backtest.TradingStrategy;
import com.quantfinlib.core.BarSeries;
import com.quantfinlib.indicators.Indicators;

/**
 * EMA crossover: buy when the fast EMA crosses above the slow EMA, sell on the reverse cross.
 *
 * @deprecated since 2.4.0, removed from the library in 3.0 (owner decision D3:
 *     sample strategies are not library function). An identical copy is
 *     {@code com.quantfinlib.examples.strategies.EmaCrossStrategy} in the {@code examples} jar;
 *     copy it into your own code. {@link SmaCrossStrategy} stays.
 */
@Deprecated(since = "2.4.0", forRemoval = true)
public final class EmaCrossStrategy implements TradingStrategy {

    private final int fastPeriod;
    private final int slowPeriod;
    private double[] fast;
    private double[] slow;

    public EmaCrossStrategy(int fastPeriod, int slowPeriod) {
        if (fastPeriod >= slowPeriod) {
            throw new IllegalArgumentException("fastPeriod must be < slowPeriod");
        }
        this.fastPeriod = fastPeriod;
        this.slowPeriod = slowPeriod;
    }

    @Override
    public String name() {
        return "EMA_CROSS(" + fastPeriod + "," + slowPeriod + ")";
    }

    @Override
    public void init(BarSeries series) {
        fast = Indicators.ema(series.closesCopy(), fastPeriod);
        slow = Indicators.ema(series.closesCopy(), slowPeriod);
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
