package com.quantfinlib.cli;

import com.quantfinlib.backtest.Signal;
import com.quantfinlib.backtest.TradingStrategy;
import com.quantfinlib.core.BarSeries;
import com.quantfinlib.indicators.Indicators;

/**
 * MACD signal-line crossover: buy when MACD crosses above its signal line, sell on the reverse.
 *
 * <p>Private copy for the {@code --strategy} option of {@link Main}, so
 * command lines written against 2.x keep working. The public sample is
 * {@code com.quantfinlib.examples.strategies.MacdStrategy} in the
 * {@code examples} jar; the library type was removed in 3.0 (decision D3).</p>
 */
final class MacdStrategy implements TradingStrategy {

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
        Indicators.Macd m = Indicators.macd(series.closesCopy(), fast, slow, signalPeriod);
        line = m.lineCopy();
        signal = m.signalCopy();
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
