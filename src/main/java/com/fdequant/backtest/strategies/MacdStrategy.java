package com.fdequant.backtest.strategies;

import com.fdequant.backtest.Signal;
import com.fdequant.backtest.TradingStrategy;
import com.fdequant.core.BarSeries;
import com.fdequant.indicators.Indicators;

/** MACD signal-line crossover: buy when MACD crosses above its signal line, sell on the reverse. */
public final class MacdStrategy implements TradingStrategy {

    private final int fast;
    private final int slow;
    private final int signalPeriod;
    private double[] line;
    private double[] signal;

    public MacdStrategy(int fast, int slow, int signalPeriod) {
        this.fast = fast;
        this.slow = slow;
        this.signalPeriod = signalPeriod;
    }

    public MacdStrategy() {
        this(12, 26, 9);
    }

    @Override
    public String name() {
        return "MACD(" + fast + "," + slow + "," + signalPeriod + ")";
    }

    @Override
    public void init(BarSeries series) {
        Indicators.Macd m = Indicators.macd(series.closes(), fast, slow, signalPeriod);
        line = m.line();
        signal = m.signal();
    }

    @Override
    public Signal onBar(int i) {
        if (i < 1 || Double.isNaN(signal[i]) || Double.isNaN(signal[i - 1])) {
            return Signal.HOLD;
        }
        if (line[i - 1] <= signal[i - 1] && line[i] > signal[i]) {
            return Signal.BUY;
        }
        if (line[i - 1] >= signal[i - 1] && line[i] < signal[i]) {
            return Signal.SELL;
        }
        return Signal.HOLD;
    }
}
