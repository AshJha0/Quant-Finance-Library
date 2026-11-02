package com.quantfinlib.cli;

import com.quantfinlib.backtest.Signal;
import com.quantfinlib.backtest.TradingStrategy;
import com.quantfinlib.core.BarSeries;
import com.quantfinlib.indicators.Indicators;

/**
 * RSI mean reversion: buy when RSI crosses up through the oversold level,
 * sell when it crosses down through the overbought level.
 *
 * <p>Private copy for the {@code --strategy} option of {@link Main}, so
 * command lines written against 2.x keep working. The public sample is
 * {@code com.quantfinlib.examples.strategies.RsiStrategy} in the
 * {@code examples} jar; the library type was removed in 3.0 (decision D3).</p>
 */
final class RsiStrategy implements TradingStrategy {

    private final int period;
    private final double oversold;
    private final double overbought;
    private double[] rsi;

    public RsiStrategy(int period, double oversold, double overbought) {
        this.period = period;
        this.oversold = oversold;
        this.overbought = overbought;
    }

    @Override
    public String name() {
        return "RSI(" + period + "," + oversold + "," + overbought + ")";
    }

    @Override
    public void init(BarSeries series) {
        rsi = Indicators.rsi(series.closesCopy(), period);
    }

    @Override
    public Signal onBar(int i) {
        if (i < 1 || Double.isNaN(rsi[i]) || Double.isNaN(rsi[i - 1])) {
            return Signal.HOLD;
        }
        if (rsi[i - 1] <= oversold && rsi[i] > oversold) {
            return Signal.BUY;
        }
        if (rsi[i - 1] >= overbought && rsi[i] < overbought) {
            return Signal.SELL;
        }
        return Signal.HOLD;
    }
}
