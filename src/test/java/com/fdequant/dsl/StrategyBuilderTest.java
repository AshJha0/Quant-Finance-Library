package com.fdequant.dsl;

import com.fdequant.TestData;
import com.fdequant.backtest.BacktestResult;
import com.fdequant.core.BarSeries;
import com.fdequant.indicators.Indicators;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrategyBuilderTest {

    @Test
    void dslStrategyBacktestsEndToEnd() {
        BarSeries s = BarSeries.of("CYCLE", TestData.sineTrend(600, 100, 0.02, 12, 80));
        double[] close = s.closes();
        double[] fast = Indicators.sma(close, 5);
        double[] slow = Indicators.sma(close, 25);

        BacktestResult result = StrategyBuilder.named("SMA DSL")
                .enterWhen(Rules.crossAbove(fast, slow))
                .exitWhen(Rules.crossBelow(fast, slow))
                .withStopLoss(0.10)
                .withTakeProfit(0.25)
                .build()
                .backtest(s, 50_000);

        assertEquals("SMA DSL", result.strategyName());
        assertTrue(result.trades().size() >= 2);
        assertEquals(s.size(), result.equityCurve().length);
    }

    @Test
    void rulesComposeWithAndOrNot() {
        double[] a = {1, 2, 3, 4};
        double[] b = {2, 2, 2, 2};
        Rule crossed = Rules.crossAbove(a, b);
        assertTrue(crossed.isSatisfied(2));
        assertTrue(crossed.not().isSatisfied(1));
        assertTrue(crossed.and(Rules.aboveValue(a, 2.5)).isSatisfied(2));
        assertTrue(crossed.or(Rules.belowValue(a, 0)).isSatisfied(2));
        assertTrue(Rules.rising(a, 2).isSatisfied(3));
    }

    @Test
    void nanWarmupNeverTriggersRules() {
        double[] withNan = {Double.NaN, Double.NaN, 5, 6};
        double[] level = {4, 4, 4, 4};
        Rule r = Rules.crossAbove(withNan, level);
        assertTrue(!r.isSatisfied(1) && !r.isSatisfied(2));
    }

    @Test
    void entryRuleRequired() {
        assertThrows(NullPointerException.class,
                () -> StrategyBuilder.named("broken").build());
    }
}
