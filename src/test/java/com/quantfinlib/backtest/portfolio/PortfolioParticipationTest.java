package com.quantfinlib.backtest.portfolio;

import com.quantfinlib.backtest.FillPolicy;
import com.quantfinlib.core.BarSeries;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The participation cap: a capped target is worked over several bars at exactly the cap. */
class PortfolioParticipationTest {

    private static BarSeries flat(int bars, double volume) {
        BarSeries.Builder b = BarSeries.builder("A");
        for (int i = 0; i < bars; i++) {
            b.add(i * 86_400_000L, 100, 100, 100, 100, volume);
        }
        return b.build();
    }

    private static PortfolioStrategy allIn() {
        return new PortfolioStrategy() {
            @Override
            public String name() {
                return "all-in";
            }

            @Override
            public void init(Map<String, BarSeries> data) {
            }

            @Override
            public Map<String, Double> targetWeights(int index) {
                return Map.of("A", 1.0);
            }
        };
    }

    private static double finalQty(FillPolicy policy, PortfolioBacktester.Participation cap,
                                   int bars) {
        Map<String, BarSeries> data = Map.of("A", flat(bars, 1_000));
        PortfolioBacktester.Config cfg = new PortfolioBacktester.Config(1_000_000, 0, 1_000,
                252, null).withFillPolicy(policy);
        return PortfolioBacktester.run(allIn(), data, data, cfg, null, Map.of(), cap)
                .finalPositions().getOrDefault("A", 0.0);
    }

    @Test
    void cappedTargetIsWorkedAtTheCapEachBar() {
        // Target 10,000 shares; cap 10% of a 1,000-share ADV = 100 shares per bar.
        PortfolioBacktester.Participation cap = new PortfolioBacktester.Participation(0.1, 5);
        // NEXT_OPEN: fills on bars 1..5 -> 500 shares
        assertEquals(500, finalQty(FillPolicy.NEXT_OPEN, cap, 6), 1e-9);
        // CLOSE: bar 0 has no ADV history (no trade), then bars 1..5 -> 500 shares
        assertEquals(500, finalQty(FillPolicy.CLOSE, cap, 6), 1e-9);
        // Without the cap the whole target fills at once.
        assertEquals(10_000, finalQty(FillPolicy.NEXT_OPEN, null, 6), 1e-9);
        // A loose cap stops binding once the target is reached.
        assertEquals(10_000, finalQty(FillPolicy.NEXT_OPEN,
                new PortfolioBacktester.Participation(4.0, 5), 6), 1e-9);
    }

    @Test
    void rejectsInvalidCap() {
        assertThrows(IllegalArgumentException.class,
                () -> new PortfolioBacktester.Participation(0, 5));
        assertThrows(IllegalArgumentException.class,
                () -> new PortfolioBacktester.Participation(0.1, 0));
    }
}
