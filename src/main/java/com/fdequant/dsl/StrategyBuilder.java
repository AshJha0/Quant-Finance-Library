package com.fdequant.dsl;

import com.fdequant.backtest.BacktestConfig;
import com.fdequant.backtest.BacktestResult;
import com.fdequant.backtest.Backtester;
import com.fdequant.backtest.Signal;
import com.fdequant.backtest.TradingStrategy;
import com.fdequant.core.BarSeries;

import java.util.Objects;

/**
 * Strategy Builder DSL: compose entry/exit rules, stop loss and take profit
 * into a backtestable strategy with a fluent API.
 *
 * <pre>{@code
 * double[] close = series.closes();
 * double[] fast = Indicators.ema(close, 12);
 * double[] slow = Indicators.ema(close, 26);
 *
 * BacktestResult result = StrategyBuilder.named("EMA momentum")
 *         .enterWhen(Rules.crossAbove(fast, slow))
 *         .exitWhen(Rules.crossBelow(fast, slow))
 *         .withStopLoss(0.03)
 *         .withTakeProfit(0.08)
 *         .build()
 *         .backtest(series, 100_000);
 * }</pre>
 */
public final class StrategyBuilder {

    private final String name;
    private Rule entryRule;
    private Rule exitRule;
    private double stopLossPct;
    private double takeProfitPct;

    private StrategyBuilder(String name) {
        this.name = name;
    }

    public static StrategyBuilder named(String name) {
        return new StrategyBuilder(name);
    }

    public StrategyBuilder enterWhen(Rule rule) {
        this.entryRule = rule;
        return this;
    }

    public StrategyBuilder exitWhen(Rule rule) {
        this.exitRule = rule;
        return this;
    }

    /** Per-trade stop loss as a fraction of the entry price (0.03 = 3%). */
    public StrategyBuilder withStopLoss(double pct) {
        this.stopLossPct = pct;
        return this;
    }

    /** Per-trade take profit as a fraction of the entry price (0.08 = 8%). */
    public StrategyBuilder withTakeProfit(double pct) {
        this.takeProfitPct = pct;
        return this;
    }

    public DslStrategy build() {
        Objects.requireNonNull(entryRule, "entry rule is required");
        Rule exit = exitRule != null ? exitRule : i -> false;
        return new DslStrategy(name, entryRule, exit, stopLossPct, takeProfitPct);
    }

    /** A rule-based strategy produced by the builder. */
    public static final class DslStrategy implements TradingStrategy {

        private final String name;
        private final Rule entry;
        private final Rule exit;
        private final double stopLossPct;
        private final double takeProfitPct;

        private DslStrategy(String name, Rule entry, Rule exit, double stopLossPct, double takeProfitPct) {
            this.name = name;
            this.entry = entry;
            this.exit = exit;
            this.stopLossPct = stopLossPct;
            this.takeProfitPct = takeProfitPct;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void init(BarSeries series) {
            // Rules close over precomputed indicator arrays; nothing to do.
        }

        @Override
        public Signal onBar(int index) {
            if (entry.isSatisfied(index)) {
                return Signal.BUY;
            }
            if (exit.isSatisfied(index)) {
                return Signal.SELL;
            }
            return Signal.HOLD;
        }

        @Override
        public double stopLossPct() {
            return stopLossPct;
        }

        @Override
        public double takeProfitPct() {
            return takeProfitPct;
        }

        /** Convenience: run a backtest with default costs and this strategy's risk settings. */
        public BacktestResult backtest(BarSeries series, double initialCapital) {
            return Backtester.run(this, series, BacktestConfig.defaults().withInitialCapital(initialCapital));
        }

        public BacktestResult backtest(BarSeries series, BacktestConfig config) {
            return Backtester.run(this, series, config);
        }
    }
}
