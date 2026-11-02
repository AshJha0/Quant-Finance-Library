package com.quantfinlib.cli;

import com.quantfinlib.backtest.Signal;
import com.quantfinlib.backtest.TradingStrategy;
import com.quantfinlib.core.BarSeries;
import com.quantfinlib.indicators.Indicators;

/**
 * Bollinger Band mean reversion: buy when the close dips below the lower band,
 * sell when it recovers to the middle band or stretches above the upper band.
 *
 * <p>Private copy for the {@code --strategy} option of {@link Main}, so
 * command lines written against 2.x keep working. The public sample is
 * {@code com.quantfinlib.examples.strategies.BollingerBandsStrategy} in the
 * {@code examples} jar; the library type was removed in 3.0 (decision D3).</p>
 */
final class BollingerBandsStrategy implements TradingStrategy {

    private final int period;
    private final double k;
    private double[] upper;
    private double[] middle;
    private double[] lower;
    private double[] close;

    public BollingerBandsStrategy(int period, double k) {
        this.period = period;
        this.k = k;
    }

    public BollingerBandsStrategy() {
        this(20, 2.0);
    }

    @Override
    public String name() {
        return "BOLLINGER(" + period + "," + k + ")";
    }

    @Override
    public void init(BarSeries series) {
        close = series.closesCopy();
        Indicators.Bollinger b = Indicators.bollinger(close, period, k);
        upper = b.upperCopy();
        middle = b.middleCopy();
        lower = b.lowerCopy();
    }

    @Override
    public Signal onBar(int i) {
        if (Double.isNaN(middle[i])) {
            return Signal.HOLD;
        }
        if (close[i] < lower[i]) {
            return Signal.BUY;
        }
        if ((close[i] >= middle[i] && close[i] > upper[i])
                || (close[i] >= middle[i] && i > 0 && close[i - 1] < middle[i - 1])) {
            return Signal.SELL;
        }
        return Signal.HOLD;
    }
}
