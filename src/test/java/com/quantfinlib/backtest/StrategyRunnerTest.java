package com.quantfinlib.backtest;

import com.quantfinlib.core.BarSeries;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Replay equality: one strategy, one tape, three engines, the same orders and fills. */
class StrategyRunnerTest {

    @TempDir
    Path dir;

    /** Buys after an up-close, sells after a down-close. */
    static TradingStrategy upDown() {
        return new TradingStrategy() {
            BarSeries s;

            @Override
            public String name() {
                return "up-down";
            }

            @Override
            public void init(BarSeries series) {
                s = series;
            }

            @Override
            public Signal onBar(int index) {
                if (index == 0) {
                    return Signal.HOLD;
                }
                double d = s.close(index) - s.close(index - 1);
                return d > 0 ? Signal.BUY : d < 0 ? Signal.SELL : Signal.HOLD;
            }
        };
    }

    static BarSeries tape(long seed, boolean powersOfTwo) {
        SplittableRandom rnd = new SplittableRandom(seed);
        BarSeries.Builder b = BarSeries.builder("TAPE");
        int e = 6;
        double p = 100;
        for (int i = 0; i < 80; i++) {
            if (powersOfTwo) {
                e = Math.max(5, Math.min(8, e + (rnd.nextBoolean() ? 1 : -1)));
                p = Math.pow(2, e);
            } else {
                p = Math.round(p * (1 + 0.02 * (rnd.nextDouble() - 0.5)) * 100) / 100.0;
            }
            b.add(i * 60_000_000_000L, p, p, p, p, 1_000);
        }
        return b.build();
    }

    List<StrategyRunner.Run> all(BarSeries tape, double capital) throws IOException {
        return List.of(
                StrategyRunner.run(StrategyRunner.Engine.BAR_BACKTESTER, upDown(), tape, capital,
                        dir),
                StrategyRunner.run(StrategyRunner.Engine.TICK_BACKTESTER, upDown(), tape,
                        capital, dir),
                StrategyRunner.run(StrategyRunner.Engine.PAPER_GATEWAY, upDown(), tape, capital,
                        dir));
    }

    @Test
    void identicalOrdersAndFillsOnAWholeShareTape() throws IOException {
        // prices are powers of two and capital is 2^20, so cash/price is always whole
        for (long seed = 1; seed <= 5; seed++) {
            List<StrategyRunner.Run> runs = all(tape(seed, true), 1 << 20);
            List<StrategyRunner.Fill> bar = runs.get(0).fills();
            assertTrue(bar.size() >= 6, "fills " + bar.size());
            for (StrategyRunner.Run r : runs) {
                assertEquals(bar, r.fills(), r.engine() + " seed " + seed);
                assertEquals(runs.get(0).finalEquity(), r.finalEquity(), 1e-9);
            }
        }
    }

    @Test
    void tickAndPaperAgreeExactlyAndMatchTheBarEngineOnTimesSidesAndPrices()
            throws IOException {
        List<StrategyRunner.Run> runs = all(tape(9, false), 1_000_000);
        List<StrategyRunner.Fill> bar = runs.get(0).fills();
        List<StrategyRunner.Fill> tick = runs.get(1).fills();
        List<StrategyRunner.Fill> paper = runs.get(2).fills();
        assertEquals(tick, paper);
        assertEquals(runs.get(1).finalEquity(), runs.get(2).finalEquity(), 1e-9);
        assertEquals(bar.size(), tick.size());
        for (int i = 0; i < bar.size(); i++) {
            assertEquals(bar.get(i).index(), tick.get(i).index());
            assertEquals(bar.get(i).side(), tick.get(i).side());
            assertEquals(bar.get(i).price(), tick.get(i).price(), 0);
            // whole shares: the bar engine's fractional quantity, floored
            assertEquals(Math.floor(bar.get(i).quantity()), tick.get(i).quantity(), 1);
        }
        assertTrue(runs.get(0).toString().contains("BAR_BACKTESTER"));
    }

    @Test
    void rejectsStopsAndBadInputs() {
        TradingStrategy stopped = new TradingStrategy() {
            @Override
            public String name() {
                return "s";
            }

            @Override
            public void init(BarSeries series) {
            }

            @Override
            public Signal onBar(int index) {
                return Signal.HOLD;
            }

            @Override
            public double stopLossPct() {
                return 0.05;
            }
        };
        BarSeries t = tape(1, true);
        assertThrows(IllegalArgumentException.class, () -> StrategyRunner.run(
                StrategyRunner.Engine.BAR_BACKTESTER, stopped, t, 1000, dir));
        assertThrows(IllegalArgumentException.class, () -> StrategyRunner.run(
                StrategyRunner.Engine.PAPER_GATEWAY, upDown(), t, 0, dir));
        assertThrows(NullPointerException.class,
                () -> new StrategyRunner.Fill(0, null, 1, 1));
    }

    List<StrategyRunner.Run> all(BarSeries tape, double capital, StrategyRunner.Sizing sizing)
            throws IOException {
        return List.of(
                StrategyRunner.run(StrategyRunner.Engine.BAR_BACKTESTER, upDown(), tape, capital,
                        dir, sizing),
                StrategyRunner.run(StrategyRunner.Engine.TICK_BACKTESTER, upDown(), tape,
                        capital, dir, sizing),
                StrategyRunner.run(StrategyRunner.Engine.PAPER_GATEWAY, upDown(), tape, capital,
                        dir, sizing));
    }

    @Test
    void wholeShareSizingGivesExactThreeEngineEqualityOnNonIntegerCashAndPrice()
            throws IOException {
        for (StrategyRunner.Sizing sizing : List.of(StrategyRunner.Sizing.wholeShares(),
                StrategyRunner.Sizing.lots(100))) {
            for (long seed = 1; seed <= 5; seed++) {
                // cents prices, capital with cents: cash/price is essentially never whole
                List<StrategyRunner.Run> runs = all(tape(seed, false), 1_000_003.37, sizing);
                List<StrategyRunner.Fill> bar = runs.get(0).fills();
                assertTrue(bar.size() >= 6, "fills " + bar.size());
                boolean sawRemainder = false;
                for (StrategyRunner.Fill f : bar) {
                    assertEquals(0, f.quantity() % sizing.lotSize(), 0);
                    sawRemainder |= f.quantity() > 0;
                }
                assertTrue(sawRemainder);
                for (StrategyRunner.Run r : runs) {
                    assertEquals(bar, r.fills(), r.engine() + " seed " + seed + " " + sizing);
                    assertEquals(runs.get(0).finalEquity(), r.finalEquity(), 1e-6);
                }
            }
        }
    }

    @Test
    void wholeSharesWorkedExample() throws IOException {
        BarSeries.Builder b = BarSeries.builder("W");
        double[] px = {29, 30, 31};
        for (int i = 0; i < px.length; i++) {
            b.add(i, px[i], px[i], px[i], px[i], 10);
        }
        for (StrategyRunner.Run r : all(b.build(), 1000, StrategyRunner.Sizing.wholeShares())) {
            assertEquals(33, r.fills().get(0).quantity(), 0, r.engine().toString());
            // 1000 - 33*30 + 33*31 = 1033
            assertEquals(1033, r.finalEquity(), 1e-9, r.engine().toString());
        }
    }

    @Test
    void fractionalSizingKeepsThe21BarEngine() throws IOException {
        BarSeries t = tape(9, false);
        StrategyRunner.Run frac = StrategyRunner.run(StrategyRunner.Engine.BAR_BACKTESTER,
                upDown(), t, 1_000_000, dir, StrategyRunner.Sizing.fractionalShares());
        BacktestResult direct = Backtester.run(upDown(), t,
                new BacktestConfig(1_000_000, 0, 0, 0, 0, 252, FillPolicy.CLOSE));
        assertEquals(direct.trades().get(0).quantity(), frac.fills().get(0).quantity(), 0);
        assertTrue(frac.fills().get(0).quantity() % 1 != 0);
        assertThrows(IllegalArgumentException.class, () -> StrategyRunner.Sizing.lots(0));
        assertThrows(IllegalArgumentException.class, () -> Backtester.runWholeLots(upDown(), t,
                new BacktestConfig(1, 0, 0, 0, 0, 252, FillPolicy.CLOSE), 0));
    }
}
