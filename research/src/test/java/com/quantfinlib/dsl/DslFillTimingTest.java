package com.quantfinlib.dsl;

import com.quantfinlib.backtest.BacktestConfig;
import com.quantfinlib.backtest.BacktestResult;
import com.quantfinlib.backtest.Trade;
import com.quantfinlib.core.BarSeries;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Fill timing of DSL strategies (moved from core {@code backtest.FillTimingTest} in 4.0). */
class DslFillTimingTest {

    @Test
    void dslExitIsNotMaskedByALevelEntryRule() {
        // Entry: "price above 50" holds on every bar. Exit fires on bar 5.
        double[] closes = new double[10];
        java.util.Arrays.fill(closes, 100);
        BarSeries s = BarSeries.of("X", closes);
        Rule always = i -> true;
        Rule exitAt5 = i -> i == 5;
        BacktestResult r = StrategyBuilder.named("level").enterWhen(always).exitWhen(exitAt5)
                .build().backtest(s, BacktestConfig.frictionless());
        // Old behaviour: BUY masks the SELL, the position is only closed at end of data.
        assertEquals(Trade.REASON_SIGNAL, r.trades().getFirst().exitReason());
        assertEquals(6, r.trades().getFirst().exitIndex()); // signal at 5, next open
        // Flat again after the exit: the always-true entry re-enters.
        assertEquals(2, r.trades().size());
    }

}
